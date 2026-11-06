package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential

/**
 * Obtains a short-lived credential from the trusted Galaxy backend.
 *
 * Implementations must never embed or return the long-lived provider API key.
 * The returned credential must be kept in memory and must not be persisted.
 */
interface RealtimeSessionProvider {
    suspend fun createSession(
        characterId: String,
        voiceId: String?
    ): RealtimeSessionCredential
}
