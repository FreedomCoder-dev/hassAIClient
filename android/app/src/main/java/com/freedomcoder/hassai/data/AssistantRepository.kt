package com.freedomcoder.hassai.data

import android.util.Log
import com.freedomcoder.hassai.model.ChatMessage
import com.freedomcoder.hassai.model.MessageRole
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

private const val TAG = "AssistantRepository"

class AssistantRepository(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val accessToken: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    fun streamResponse(
        conversationId: String?,
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        voicePreferred: Boolean,
    ): Flow<AssistantEvent> = callbackFlow {
        val payload = ChatRequest(
            conversationId = conversationId,
            voice = voicePreferred,
            messages = (history + newMessage).map { it.toPayload() },
        )
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "text/event-stream")
            .post(
                json.encodeToString(payload)
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        val eventSource: EventSource = EventSources.createFactory(client)
            .newEventSource(request, object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    try {
                        val serverEvent = json.decodeFromString(ServerEvent.serializer(), data)
                        trySend(AssistantEvent.Delta(serverEvent))
                    } catch (error: Throwable) {
                        Log.w(TAG, "Failed to decode event", error)
                        trySend(AssistantEvent.Error(error))
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    val error = t ?: IllegalStateException("Unknown SSE failure")
                    Log.e(TAG, "SSE failed", error)
                    trySend(AssistantEvent.Error(error))
                    close(error)
                }
            })

        awaitClose { eventSource.cancel() }
    }
        .flowOn(Dispatchers.IO)

    private fun ChatMessage.toPayload(): MessagePayload = MessagePayload(
        id = id,
        role = when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.TOOL -> "tool"
            MessageRole.SYSTEM -> "system"
        },
        content = text,
    )
}

sealed interface AssistantEvent {
    data class Delta(val event: ServerEvent) : AssistantEvent
    data class Error(val throwable: Throwable) : AssistantEvent
}

@Serializable
private data class ChatRequest(
    @SerialName("conversation_id") val conversationId: String?,
    val voice: Boolean,
    val messages: List<MessagePayload>,
)

@Serializable
private data class MessagePayload(
    val id: String,
    val role: String,
    val content: String,
)

@Serializable
data class ServerEvent(
    val type: String,
    val content: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val status: String? = null,
)
