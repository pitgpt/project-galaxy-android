package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.ConversationMessage

/** Storage boundary for character-specific conversation history. */
interface ConversationRepository {
    suspend fun getForCharacter(characterId: String): List<ConversationMessage>
    suspend fun append(message: ConversationMessage)
    suspend fun appendTurn(userMessage: ConversationMessage, assistantMessage: ConversationMessage) {
        require(userMessage.characterId == assistantMessage.characterId)
        append(userMessage)
        append(assistantMessage)
    }
    suspend fun replaceForCharacter(characterId: String, messages: List<ConversationMessage>) {
        require(messages.all { it.characterId == characterId })
        clearForCharacter(characterId)
        messages.forEach { append(it) }
    }
    suspend fun clearForCharacter(characterId: String)
}
