package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential
import com.pitgpt.galaxy.core.repository.RealtimeSessionProvider

/**
 * Validates the requested character and rejects credentials too close to expiry.
 * The provider is responsible for authentication and server-side policy.
 */
class CreateRealtimeSession(
    private val provider: RealtimeSessionProvider,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val minimumRemainingValidityMillis: Long = DEFAULT_MINIMUM_REMAINING_VALIDITY_MILLIS
) {
    init {
        require(minimumRemainingValidityMillis >= 0L) {
            "Minimum remaining validity must not be negative"
        }
    }

    suspend operator fun invoke(
        characterId: String,
        voiceId: String?,
        personaInstructions: String? = null
    ): RealtimeSessionCredential {
        require(characterId.isNotBlank()) { "Character ID must not be blank" }
        require(voiceId == null || voiceId.isNotBlank()) { "Voice ID must not be blank" }

        require(personaInstructions == null || personaInstructions.length <= MAX_PERSONA_INSTRUCTIONS_LENGTH) {
            "Character persona instructions are too long"
        }

        val credential = provider.createSession(characterId, voiceId, personaInstructions)
        check(credential.expiresAtEpochMillis - now() > minimumRemainingValidityMillis) {
            "Realtime session credential is expired or too close to expiry"
        }
        return credential
    }

    private companion object {
        const val DEFAULT_MINIMUM_REMAINING_VALIDITY_MILLIS = 30_000L
        const val MAX_PERSONA_INSTRUCTIONS_LENGTH = 8_000
    }
}
