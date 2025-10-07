package com.freedomcoder.hassai.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freedomcoder.hassai.data.AssistantEvent
import com.freedomcoder.hassai.data.AssistantRepository
import com.freedomcoder.hassai.model.ChatMessage
import com.freedomcoder.hassai.model.MessageRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AssistViewModel(
    private val repository: AssistantRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistUiState())
    val uiState: StateFlow<AssistUiState> = _uiState.asStateFlow()

    private var activeStream: Job? = null
    private var activeAssistantMessageId: String? = null

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun toggleListening(listening: Boolean) {
        _uiState.update { it.copy(isListening = listening) }
    }

    fun onTranscription(text: String, isFinal: Boolean) {
        _uiState.update { it.copy(inputText = text) }
        if (isFinal && text.isNotBlank()) {
            sendMessage(text)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val message = ChatMessage(role = MessageRole.USER, text = text)
        _uiState.update {
            it.copy(
                inputText = "",
                messages = it.messages + message,
                error = null,
            )
        }
        streamResponse(message)
    }

    private fun streamResponse(userMessage: ChatMessage) {
        activeStream?.cancel()
        activeAssistantMessageId = null
        activeStream = viewModelScope.launch {
            _uiState.update { it.copy(isStreaming = true, status = "Connecting…", error = null) }
            repository.streamResponse(
                conversationId = _uiState.value.conversationId,
                history = _uiState.value.messages.dropLast(1),
                newMessage = userMessage,
                voicePreferred = _uiState.value.isListening,
            ).collect { event ->
                when (event) {
                    is AssistantEvent.Delta -> handleServerEvent(event.event)
                    is AssistantEvent.Error -> handleError(event.throwable)
                }
            }
        }
    }

    private fun handleServerEvent(event: com.freedomcoder.hassai.data.ServerEvent) {
        when (event.type) {
            "status" -> _uiState.update { it.copy(status = event.content) }
            "conversation" -> _uiState.update { it.copy(conversationId = event.conversationId ?: event.content) }
            "token", "delta" -> appendAssistantText(event.content.orEmpty())
            "complete" -> _uiState.update {
                val id = activeAssistantMessageId
                val updatedMessages = if (id != null) {
                    it.messages.map { message ->
                        if (message.id == id) message.copy(isStreaming = false) else message
                    }
                } else it.messages
                it.copy(
                    messages = updatedMessages,
                    isStreaming = false,
                    status = "Ready",
                )
            }
            "error" -> handleError(IllegalStateException(event.content ?: "Unknown error"))
        }
    }

    private fun appendAssistantText(delta: String) {
        if (delta.isEmpty()) return
        var messageId = activeAssistantMessageId
        if (messageId == null) {
            val assistantMessage = ChatMessage(role = MessageRole.ASSISTANT, text = delta, isStreaming = true)
            messageId = assistantMessage.id
            activeAssistantMessageId = messageId
            _uiState.update {
                it.copy(
                    messages = it.messages + assistantMessage,
                    status = "Responding…",
                )
            }
            return
        }

        val id = messageId
        _uiState.update {
            it.copy(
                messages = it.messages.map { message ->
                    if (message.id == id) {
                        message.copy(text = message.text + delta)
                    } else {
                        message
                    }
                }
            )
        }
    }

    private fun handleError(throwable: Throwable) {
        activeAssistantMessageId = null
        _uiState.update {
            it.copy(
                isStreaming = false,
                status = "Error",
                error = throwable.message ?: throwable.toString(),
            )
        }
    }
}
