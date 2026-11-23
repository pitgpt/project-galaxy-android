package com.pitgpt.galaxy.core.model

/** Metadata for an image persisted in encrypted app-private storage. */
data class StoredGeneratedImage(
    val assetId: String,
    val requestId: String,
    val conversationId: String,
    val characterId: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val createdAtEpochMillis: Long
) {
    init {
        require(assetId.isNotBlank() && requestId.isNotBlank()) { "Image identifiers must not be blank" }
        require(conversationId.isNotBlank() && characterId.isNotBlank()) { "Image ownership must not be blank" }
        require(mimeType in setOf("image/png", "image/jpeg", "image/webp")) { "Image MIME type is unsupported" }
        require(width > 0 && height > 0 && byteSize > 0) { "Image dimensions and size must be positive" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Image SHA-256 is invalid" }
    }
}
