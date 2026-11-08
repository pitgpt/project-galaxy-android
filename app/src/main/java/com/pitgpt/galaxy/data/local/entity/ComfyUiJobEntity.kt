package com.pitgpt.galaxy.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord

@Entity(
    tableName = "comfyui_jobs",
    indices = [Index(value = ["requestId"])]
)
data class ComfyUiJobEntity(
    @PrimaryKey val promptId: String,
    val requestId: String,
    val createdAtEpochMillis: Long,
    val cancelled: Boolean
)

fun ComfyUiJobEntity.toDomain() = ComfyUiJobRecord(
    promptId = promptId,
    requestId = requestId,
    createdAtEpochMillis = createdAtEpochMillis,
    cancelled = cancelled
)

fun ComfyUiJobRecord.toEntity() = ComfyUiJobEntity(
    promptId = promptId,
    requestId = requestId,
    createdAtEpochMillis = createdAtEpochMillis,
    cancelled = cancelled
)
