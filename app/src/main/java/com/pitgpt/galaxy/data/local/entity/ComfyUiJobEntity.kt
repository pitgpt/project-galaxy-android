package com.pitgpt.galaxy.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "comfyui_jobs",
    indices = [Index(value = ["requestId"])]
)
data class ComfyUiJobEntity(
    @PrimaryKey val promptId: String,
    val requestId: String,
    val createdAtEpochMillis: Long,
    val cancelled: Boolean,
    @ColumnInfo(defaultValue = "0") val submissionStarted: Boolean = false
)
