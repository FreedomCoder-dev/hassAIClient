package com.freedomcoder.hassai.ui

import com.freedomcoder.hassai.model.ChatMessage

data class AssistUiState(
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val isListening: Boolean = true,
    val status: String? = null,
    val error: String? = null,
)
