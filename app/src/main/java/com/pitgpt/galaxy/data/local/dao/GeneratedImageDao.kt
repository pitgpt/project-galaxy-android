package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pitgpt.galaxy.data.local.entity.GeneratedImageEntity

@Dao
interface GeneratedImageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: GeneratedImageEntity)

    @Query("SELECT * FROM generated_images WHERE assetId = :assetId LIMIT 1")
    suspend fun get(assetId: String): GeneratedImageEntity?

    @Query("SELECT * FROM generated_images WHERE requestId = :requestId ORDER BY createdAtEpochMillis ASC LIMIT 1")
    suspend fun getForRequest(requestId: String): GeneratedImageEntity?

    @Query("""
        SELECT * FROM generated_images
        WHERE conversationId = :conversationId
        ORDER BY createdAtEpochMillis DESC
    """)
    suspend fun listForConversation(conversationId: String): List<GeneratedImageEntity>

    @Query("DELETE FROM generated_images WHERE assetId = :assetId")
    suspend fun delete(assetId: String): Int
}
