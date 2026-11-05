package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pitgpt.galaxy.data.local.entity.MemoryEntity

@Dao
interface MemoryDao {
    @Query("""
        SELECT * FROM memory_items
        WHERE scope = :scope
          AND ((:ownerCharacterId IS NULL AND ownerCharacterId IS NULL)
               OR ownerCharacterId = :ownerCharacterId)
        ORDER BY updatedAtEpochMillis DESC
    """)
    suspend fun getAll(scope: String, ownerCharacterId: String?): List<MemoryEntity>

    @Upsert
    suspend fun upsert(entity: MemoryEntity)

    @Query("DELETE FROM memory_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("""
        DELETE FROM memory_items
        WHERE scope = :scope
          AND ((:ownerCharacterId IS NULL AND ownerCharacterId IS NULL)
               OR ownerCharacterId = :ownerCharacterId)
    """)
    suspend fun deleteAll(scope: String, ownerCharacterId: String?)
}
