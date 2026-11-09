package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pitgpt.galaxy.data.local.entity.ComfyUiJobEntity

@Dao
interface ComfyUiJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ComfyUiJobEntity)

    @Query("SELECT * FROM comfyui_jobs WHERE promptId = :promptId LIMIT 1")
    suspend fun get(promptId: String): ComfyUiJobEntity?

    @Query("SELECT * FROM comfyui_jobs ORDER BY createdAtEpochMillis ASC, promptId ASC")
    suspend fun getAll(): List<ComfyUiJobEntity>

    @Query("UPDATE comfyui_jobs SET cancelled = 1 WHERE promptId = :promptId")
    suspend fun markCancelled(promptId: String): Int

    @Query("DELETE FROM comfyui_jobs WHERE promptId = :promptId")
    suspend fun delete(promptId: String): Int
}
