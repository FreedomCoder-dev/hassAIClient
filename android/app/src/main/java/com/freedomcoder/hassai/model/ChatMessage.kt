package com.freedomcoder.hassai.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val isStreaming: Boolean = false,
    val timestamp: Instant = Clock.System.now(),
    val synthetic: Boolean = false,
)
