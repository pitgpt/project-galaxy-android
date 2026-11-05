package com.pitgpt.galaxy.core.model

/**
 * A portable description of an AI character.
 *
 * Keep character identity separate from the app engine so characters can
 * eventually be installed, exported, and shared independently.
 */
data class CharacterProfile(
    val id: String,
    val displayName: String,
    val description: String,
    val personaDefinition: String,
    val avatarId: String? = null,
    val voiceId: String? = null,
    val modelProviderId: String? = null,
    val isBuiltIn: Boolean = false
)
