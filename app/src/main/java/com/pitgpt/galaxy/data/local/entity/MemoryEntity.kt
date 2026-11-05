package com.pitgpt.galaxy.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pitgpt.galaxy.core.model.MemoryItem
import com.pitgpt.galaxy.core.model.MemoryScope

@Entity(
    tableName = "memory_items",
    indices = [Index(value = ["scope", "ownerCharacterId", "updatedAtEpochMillis"])]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val ownerCharacterId: String?,
    val content: String,
    val importance: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

fun MemoryEntity.toDomain() = MemoryItem(
    id = id,
    scope = MemoryScope.valueOf(scope),
    ownerCharacterId = ownerCharacterId,
    content = content,
    importance = importance,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis
)

fun MemoryItem.toEntity() = MemoryEntity(
    id = id,
    scope = scope.name,
    ownerCharacterId = ownerCharacterId,
    content = content,
    importance = importance,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis
)
