package com.pitgpt.galaxy.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pitgpt.galaxy.core.model.CharacterProfile

@Entity(tableName = "character_profiles")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val description: String,
    val personaDefinition: String,
    val avatarId: String?,
    val voiceId: String?,
    val modelProviderId: String?,
    val isBuiltIn: Boolean,
    val avatarImageUri: String?
)

fun CharacterEntity.toDomain() = CharacterProfile(
    id = id,
    displayName = displayName,
    description = description,
    personaDefinition = personaDefinition,
    avatarId = avatarId,
    voiceId = voiceId,
    modelProviderId = modelProviderId,
    isBuiltIn = isBuiltIn,
    avatarImageUri = avatarImageUri
)

fun CharacterProfile.toEntity() = CharacterEntity(
    id = id,
    displayName = displayName,
    description = description,
    personaDefinition = personaDefinition,
    avatarId = avatarId,
    voiceId = voiceId,
    modelProviderId = modelProviderId,
    isBuiltIn = isBuiltIn,
    avatarImageUri = avatarImageUri
)
