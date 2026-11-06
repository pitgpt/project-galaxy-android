package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential

/**
 * Authenticated Galaxy backend contract for creating and managing a Realtime lease.
 *
 * Implementations must never embed or return the long-lived provider API key.
 * Client credentials stay in memory and are never persisted.
 */
interface RealtimeSessionProvider {
    suspend fun createSession(
        characterId: String,
        voiceId: String?
    ): RealtimeSessionCredential

    /** Renews the authenticated account's lease for this Galaxy-generated session ID. */
    suspend fun heartbeat(sessionId: String)

    /** Releases the lease. The backend operation is intentionally idempotent. */
    suspend fun closeSession(sessionId: String)
}
