package com.pitgpt.galaxy.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pitgpt.galaxy.core.model.StoredGeneratedImage

@Entity(
    tableName = "generated_images",
    indices = [
        Index(value = ["conversationId", "createdAtEpochMillis"]),
        Index(value = ["characterId", "createdAtEpochMillis"]),
        Index(value = ["requestId"])
    ]
)
data class GeneratedImageEntity(
    @PrimaryKey val assetId: String,
    val requestId: String,
    val conversationId: String,
    val characterId: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val createdAtEpochMillis: Long
)

fun GeneratedImageEntity.toDomain() = StoredGeneratedImage(
    assetId = assetId,
    requestId = requestId,
    conversationId = conversationId,
    characterId = characterId,
    mimeType = mimeType,
    width = width,
    height = height,
    byteSize = byteSize,
    sha256 = sha256,
    createdAtEpochMillis = createdAtEpochMillis
)

fun StoredGeneratedImage.toEntity() = GeneratedImageEntity(
    assetId = assetId,
    requestId = requestId,
    conversationId = conversationId,
    characterId = characterId,
    mimeType = mimeType,
    width = width,
    height = height,
    byteSize = byteSize,
    sha256 = sha256,
    createdAtEpochMillis = createdAtEpochMillis
)
