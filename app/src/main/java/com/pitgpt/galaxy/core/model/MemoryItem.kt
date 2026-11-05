package com.pitgpt.galaxy.core.model

enum class MemoryScope {
    USER,
    CHARACTER,
    SHARED
}

/**
 * A memory record with an explicit owner scope.
 * Sensitive data handling and user-controlled deletion will be implemented
 * in the persistence layer, not hidden inside character prompts.
 */
data class MemoryItem(
    val id: String,
    val scope: MemoryScope,
    val ownerCharacterId: String? = null,
    val content: String,
    val importance: Int = 3,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)
