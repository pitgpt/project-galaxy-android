package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.repository.ConversationRepository
import java.util.UUID

/** Validates and persists a user message before returning it to the UI. */
class SendMessage(
    private val conversations: ConversationRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend operator fun invoke(characterId: String, content: String): ConversationMessage {
        val normalizedContent = content.trim()
        require(characterId.isNotBlank()) { "Character ID must not be blank" }
        require(normalizedContent.isNotEmpty()) { "Message must not be blank" }

        val message = ConversationMessage(
            id = newId(),
            characterId = characterId,
            role = MessageRole.USER,
            content = normalizedContent,
            createdAtEpochMillis = now()
        )
        conversations.append(message)
        return message
    }
}
