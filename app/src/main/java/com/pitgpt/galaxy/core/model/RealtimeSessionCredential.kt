package com.pitgpt.galaxy.core.model

/**
 * Short-lived credential used only to establish one Realtime connection.
 *
 * This is deliberately not a data class: the default data-class toString()
 * would expose the client secret in logs and crash diagnostics.
 */
class RealtimeSessionCredential(
    val clientSecret: String,
    val expiresAtEpochMillis: Long,
    val sessionId: String? = null
) {
    init {
        require(clientSecret.isNotBlank()) { "Client secret must not be blank" }
        require(expiresAtEpochMillis > 0L) { "Expiration must be a positive epoch timestamp" }
    }

    override fun toString(): String =
        "RealtimeSessionCredential(clientSecret=[REDACTED], expiresAtEpochMillis=$expiresAtEpochMillis, sessionId=$sessionId)"
}
