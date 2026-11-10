package com.pitgpt.galaxy.data.repository

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.repository.ImageGenerationRequestStore
import com.pitgpt.galaxy.data.local.dao.ImageGenerationRequestDao
import com.pitgpt.galaxy.data.local.entity.toDomain
import com.pitgpt.galaxy.data.local.entity.toEntity

/**
 * Persists the request payload separately from the minimal ComfyUI job-correlation journal.
 * The DAO must belong to Galaxy's SQLCipher-backed database; no plaintext file is created.
 */
class RoomImageGenerationRequestStore(
    private val dao: ImageGenerationRequestDao
) : ImageGenerationRequestStore {
    override suspend fun put(request: ImageGenerationRequest) {
        val existing = dao.get(request.id)
        if (existing != null) {
            check(
                existing.conversationId == request.conversationId &&
                    existing.characterId == request.characterId
            ) { "A generation request ID is already associated with different Galaxy context" }
        }
        dao.upsert(request.toEntity())
    }

    override suspend fun get(requestId: String): ImageGenerationRequest? {
        require(requestId.isNotBlank()) { "Generation request ID must not be blank" }
        return dao.get(requestId)?.toDomain()
    }

    override suspend fun all(): List<ImageGenerationRequest> =
        dao.getAll().map { it.toDomain() }

    override suspend fun latestForCharacter(characterId: String): ImageGenerationRequest? {
        require(characterId.isNotBlank()) { "Character ID must not be blank" }
        return dao.latestForCharacter(characterId)?.toDomain()
    }

    override suspend fun delete(requestId: String) {
        require(requestId.isNotBlank()) { "Generation request ID must not be blank" }
        dao.delete(requestId)
    }
}
