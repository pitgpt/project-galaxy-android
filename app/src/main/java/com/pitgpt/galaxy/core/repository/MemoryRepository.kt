package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.MemoryItem
import com.pitgpt.galaxy.core.model.MemoryScope

/** Storage boundary for user, character, and shared memories. */
interface MemoryRepository {
    suspend fun getAll(scope: MemoryScope, ownerCharacterId: String? = null): List<MemoryItem>
    suspend fun save(item: MemoryItem)
    suspend fun delete(id: String)
    suspend fun deleteAll(scope: MemoryScope, ownerCharacterId: String? = null)
}
