package com.pitgpt.galaxy.data.repository

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.data.local.dao.GeneratedImageDao
import com.pitgpt.galaxy.data.local.entity.toDomain
import com.pitgpt.galaxy.data.local.entity.toEntity
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import java.security.MessageDigest
import java.util.UUID

/**
 * Coordinates encrypted binary storage with SQLCipher-backed metadata.
 * A failed metadata insert compensates by removing the just-written ciphertext.
 */
class RoomGeneratedImageRepository(
    private val dao: GeneratedImageDao,
    private val mediaStore: EncryptedMediaStore
) : GeneratedImageRepository {
    override suspend fun save(
        request: ImageGenerationRequest,
        asset: GeneratedImageAsset,
        imageBytes: ByteArray
    ): StoredGeneratedImage {
        require(asset.requestId == request.id) { "Generated image belongs to a different request" }
        require(asset.byteSize == imageBytes.size.toLong()) { "Generated image byte size does not match its payload" }
        require(asset.mimeType in SUPPORTED_MIME_TYPES) { "Generated image MIME type is unsupported" }
        require(imageBytes.isNotEmpty()) { "Generated image payload must not be empty" }
        require(sha256(imageBytes).equals(asset.sha256, ignoreCase = true)) {
            "Generated image checksum does not match its payload"
        }

        val stored = StoredGeneratedImage(
            assetId = UUID.randomUUID().toString(),
            requestId = request.id,
            conversationId = request.conversationId,
            characterId = request.characterId,
            mimeType = asset.mimeType,
            width = asset.width,
            height = asset.height,
            byteSize = asset.byteSize,
            sha256 = asset.sha256.lowercase(),
            createdAtEpochMillis = asset.createdAtEpochMillis
        )

        mediaStore.save(stored.assetId, stored.mimeType, imageBytes)
        try {
            dao.insert(stored.toEntity())
        } catch (failure: Exception) {
            runCatching { mediaStore.delete(stored.assetId) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
        return stored
    }

    override suspend fun get(assetId: String): StoredGeneratedImage? {
        requireValidAssetId(assetId)
        return dao.get(assetId)?.toDomain()
    }

    override suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage> {
        require(conversationId.isNotBlank()) { "Conversation ID must not be blank" }
        return dao.listForConversation(conversationId).map { it.toDomain() }
    }

    override suspend fun read(assetId: String): ByteArray {
        requireValidAssetId(assetId)
        val metadata = dao.get(assetId)?.toDomain()
            ?: throw IllegalArgumentException("Generated image metadata was not found")
        val bytes = mediaStore.read(assetId, metadata.mimeType)
        check(bytes.size.toLong() == metadata.byteSize) { "Stored generated image size does not match its metadata" }
        check(sha256(bytes).equals(metadata.sha256, ignoreCase = true)) {
            "Stored generated image checksum does not match its metadata"
        }
        return bytes
    }

    override suspend fun delete(assetId: String) {
        requireValidAssetId(assetId)
        val removed = dao.delete(assetId)
        check(removed == 1) { "Generated image metadata was not found" }
        // If file deletion fails, only orphaned ciphertext remains; no metadata exposes it.
        mediaStore.delete(assetId)
    }

    private fun requireValidAssetId(assetId: String) {
        require(ASSET_ID_PATTERN.matches(assetId)) { "Generated image asset ID is invalid" }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        val ASSET_ID_PATTERN = Regex("[a-fA-F0-9-]{36}")
    }
}
