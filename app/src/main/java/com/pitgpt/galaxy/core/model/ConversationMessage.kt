package com.pitgpt.galaxy.core.model

enum class MessageRole {
    USER,
    CHARACTER,
    SYSTEM
}

/** A single message belonging to one character conversation. */
data class ConversationMessage(
    val id: String,
    val characterId: String,
    val role: MessageRole,
    val content: String,
    val createdAtEpochMillis: Long
)
