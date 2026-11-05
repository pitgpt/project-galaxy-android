package com.pitgpt.galaxy.data.repository

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.repository.ConversationRepository
import com.pitgpt.galaxy.data.local.dao.ConversationMessageDao
import com.pitgpt.galaxy.data.local.entity.toDomain
import com.pitgpt.galaxy.data.local.entity.toEntity

class RoomConversationRepository(
    private val dao: ConversationMessageDao
) : ConversationRepository {
    override suspend fun getForCharacter(characterId: String): List<ConversationMessage> =
        dao.getForCharacter(characterId).map { it.toDomain() }

    override suspend fun append(message: ConversationMessage) {
        dao.insert(message.toEntity())
    }

    override suspend fun clearForCharacter(characterId: String) {
        dao.deleteForCharacter(characterId)
    }
}
