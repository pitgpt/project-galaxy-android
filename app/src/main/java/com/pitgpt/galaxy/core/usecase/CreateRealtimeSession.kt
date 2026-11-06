package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential
import com.pitgpt.galaxy.core.repository.RealtimeSessionProvider

/**
 * Validates the requested character and rejects already-expired credentials.
 * The provider is responsible for authentication and server-side policy.
 */
class CreateRealtimeSession(
    private val provider: RealtimeSessionProvider,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend operator fun invoke(
        characterId: String,
        voiceId: String?
    ): RealtimeSessionCredential {
        require(characterId.isNotBlank()) { "Character ID must not be blank" }
        require(voiceId == null || voiceId.isNotBlank()) { "Voice ID must not be blank" }

        val credential = provider.createSession(characterId, voiceId)
        check(credential.expiresAtEpochMillis > now()) {
            "Realtime session credential is already expired"
        }
        return credential
    }
}
