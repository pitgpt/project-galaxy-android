package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.ConversationMessage

/** Storage boundary for character-specific conversation history. */
interface ConversationRepository {
    suspend fun getForCharacter(characterId: String): List<ConversationMessage>
    suspend fun append(message: ConversationMessage)
    suspend fun clearForCharacter(characterId: String)
}
