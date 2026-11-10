package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pitgpt.galaxy.data.local.entity.ImageGenerationRequestEntity

@Dao
interface ImageGenerationRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImageGenerationRequestEntity)

    @Query("SELECT * FROM image_generation_requests WHERE requestId = :requestId LIMIT 1")
    suspend fun get(requestId: String): ImageGenerationRequestEntity?

    @Query(
        "SELECT * FROM image_generation_requests " +
            "ORDER BY createdAtEpochMillis DESC, requestId ASC"
    )
    suspend fun getAll(): List<ImageGenerationRequestEntity>

    @Query("DELETE FROM image_generation_requests WHERE requestId = :requestId")
    suspend fun delete(requestId: String)
}
