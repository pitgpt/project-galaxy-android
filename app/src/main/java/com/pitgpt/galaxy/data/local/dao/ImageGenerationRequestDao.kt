package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pitgpt.galaxy.data.local.entity.ImageGenerationRequestEntity

@Dao
interface ImageGenerationRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImageGenerationRequestEntity)

    /**
     * Keeps the request-ID ownership check and write in one database transaction.
     * Concurrent callers cannot both observe a missing row and replace each other's context.
     */
    @Transaction
    suspend fun putIfContextMatches(entity: ImageGenerationRequestEntity) {
        val existing = get(entity.requestId)
        if (existing != null) {
            check(
                existing.conversationId == entity.conversationId &&
                    existing.characterId == entity.characterId
            ) { "A generation request ID is already associated with different Galaxy context" }
        }
        upsert(entity)
    }

    @Query("SELECT * FROM image_generation_requests WHERE requestId = :requestId LIMIT 1")
    suspend fun get(requestId: String): ImageGenerationRequestEntity?

    @Query(
        "SELECT * FROM image_generation_requests " +
            "ORDER BY createdAtEpochMillis DESC, requestId ASC"
    )
    suspend fun getAll(): List<ImageGenerationRequestEntity>

    @Query(
        "SELECT * FROM image_generation_requests WHERE characterId = :characterId " +
            "ORDER BY createdAtEpochMillis DESC, requestId DESC LIMIT 1"
    )
    suspend fun latestForCharacter(characterId: String): ImageGenerationRequestEntity?

    @Query("DELETE FROM image_generation_requests WHERE requestId = :requestId")
    suspend fun delete(requestId: String)
}
