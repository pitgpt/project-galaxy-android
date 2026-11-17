package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage

/**
 * Stable failure categories for a stored generated-image read.
 *
 * These categories let the UI distinguish device-key loss from missing or unauthenticated
 * ciphertext without deleting metadata or silently creating a replacement key.
 */
enum class GeneratedImageReadFailure {
    METADATA_MISSING,
    CIPHERTEXT_MISSING,
    KEY_UNAVAILABLE,
    CORRUPT_CIPHERTEXT,
    INTEGRITY_MISMATCH,
    STORAGE_UNAVAILABLE
}

class GeneratedImageReadException(
    val failure: GeneratedImageReadFailure,
    cause: Throwable? = null
) : Exception("Generated image read failed: $failure", cause)

/** Stores image bytes encrypted and keeps only metadata in the encrypted local database. */
interface GeneratedImageRepository {
    suspend fun save(
        request: ImageGenerationRequest,
        asset: GeneratedImageAsset,
        imageBytes: ByteArray
    ): StoredGeneratedImage

    /** Returns the already-persisted result for a generation request, if present. */
    suspend fun getForRequest(requestId: String): StoredGeneratedImage?

    suspend fun get(assetId: String): StoredGeneratedImage?
    suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage>
    suspend fun read(assetId: String): ByteArray
    suspend fun delete(assetId: String)
}
