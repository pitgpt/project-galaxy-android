package com.pitgpt.galaxy.core.model

/**
 * Versioned, model-independent identity configuration for Galaxy's first-party agent.
 *
 * This is curated identity data, not conversation history or model-generated reasoning.
 */
data class LilaIdentity(
    val id: String,
    val schemaVersion: Int,
    val displayName: String,
    val persona: String,
    val communicationPrinciples: List<String>,
    val longTermGoals: List<String>,
    val decisionPreferences: List<String>,
    val visualReferenceId: String? = null,
    val voiceReferenceId: String? = null
) {
    init {
        require(id.isNotBlank()) { "Identity id must not be blank" }
        require(schemaVersion > 0) { "Identity schema version must be positive" }
        require(displayName.isNotBlank()) { "Identity display name must not be blank" }
        require(persona.isNotBlank()) { "Identity persona must not be blank" }
        require(communicationPrinciples.none(String::isBlank)) {
            "Communication principles must not contain blank entries"
        }
        require(longTermGoals.none(String::isBlank)) {
            "Long-term goals must not contain blank entries"
        }
        require(decisionPreferences.none(String::isBlank)) {
            "Decision preferences must not contain blank entries"
        }
    }

    companion object {
        const val PRIMARY_ID = "lila.primary"
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
