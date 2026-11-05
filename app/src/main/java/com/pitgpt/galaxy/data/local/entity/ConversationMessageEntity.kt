package com.pitgpt.galaxy.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole

@Entity(
    tableName = "conversation_messages",
    indices = [Index(value = ["characterId", "createdAtEpochMillis"])]
)
data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long
)

fun ConversationMessageEntity.toDomain() = ConversationMessage(
    id = id,
    characterId = characterId,
    role = MessageRole.valueOf(role),
    content = content,
    createdAtEpochMillis = createdAtEpochMillis
)

fun ConversationMessage.toEntity() = ConversationMessageEntity(
    id = id,
    characterId = characterId,
    role = role.name,
    content = content,
    createdAtEpochMillis = createdAtEpochMillis
)
