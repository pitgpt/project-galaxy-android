package com.pitgpt.galaxy.data.repository

import com.pitgpt.galaxy.core.model.MemoryItem
import com.pitgpt.galaxy.core.model.MemoryScope
import com.pitgpt.galaxy.core.repository.MemoryRepository
import com.pitgpt.galaxy.data.local.dao.MemoryDao
import com.pitgpt.galaxy.data.local.entity.toDomain
import com.pitgpt.galaxy.data.local.entity.toEntity

class RoomMemoryRepository(
    private val dao: MemoryDao
) : MemoryRepository {
    override suspend fun getAll(
        scope: MemoryScope,
        ownerCharacterId: String?
    ): List<MemoryItem> =
        dao.getAll(scope.name, ownerCharacterId).map { it.toDomain() }

    override suspend fun save(item: MemoryItem) {
        dao.upsert(item.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun deleteAll(
        scope: MemoryScope,
        ownerCharacterId: String?
    ) {
        dao.deleteAll(scope.name, ownerCharacterId)
    }
}
