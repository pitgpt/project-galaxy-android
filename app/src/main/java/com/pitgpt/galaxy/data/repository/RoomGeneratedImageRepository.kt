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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun validateDecryptedGeneratedImageBytes(
    bytes: ByteArray,
    expectedByteSize: Long,
    expectedSha256: String
): ByteArray {
    try {
        check(bytes.size.toLong() == expectedByteSize) {
            "Stored generated image size does not match its metadata"
        }
        check(sha256(bytes).equals(expectedSha256, ignoreCase = true)) {
            "Stored generated image checksum does not match its metadata"
        }
        return bytes
    } catch (failure: Exception) {
        // Do not leave decrypted image bytes in memory when metadata validation fails.
        bytes.fill(0)
        throw failure
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/**
 * Coordinates encrypted binary storage with SQLCipher-backed metadata.
 * A failed metadata insert compensates by removing the just-written ciphertext.
 */
class RoomGeneratedImageRepository(
    private val dao: GeneratedImageDao,
    private val mediaStore: EncryptedMediaStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GeneratedImageRepository {
    private val saveMutex = Mutex()

    override suspend fun save(
        request: ImageGenerationRequest,
        asset: GeneratedImageAsset,
        imageBytes: ByteArray
    ): StoredGeneratedImage = saveMutex.withLock {
        require(asset.requestId == request.id) { "Generated image belongs to a different request" }
        require(asset.byteSize == imageBytes.size.toLong()) { "Generated image byte size does not match its payload" }
        require(asset.mimeType in SUPPORTED_MIME_TYPES) { "Generated image MIME type is unsupported" }
        require(imageBytes.isNotEmpty()) { "Generated image payload must not be empty" }
        require(sha256(imageBytes).equals(asset.sha256, ignoreCase = true)) {
            "Generated image checksum does not match its payload"
        }

        dao.getForRequest(request.id)?.toDomain()?.let { existing ->
            check(
                existing.conversationId == request.conversationId &&
                    existing.characterId == request.characterId
            ) { "The generation request ID is already associated with different Galaxy context" }
            check(
                existing.mimeType == asset.mimeType &&
                    existing.width == asset.width &&
                    existing.height == asset.height &&
                    existing.byteSize == asset.byteSize &&
                    existing.sha256.equals(asset.sha256, ignoreCase = true) &&
                    existing.createdAtEpochMillis == asset.createdAtEpochMillis
            ) { "A different image is already persisted for this generation request" }
            return@withLock existing
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

        var mediaSaved = false
        try {
            // Finish the atomic ciphertext write even if the caller is cancelled, so the
            // compensation path can reliably remove it before cancellation is rethrown.
            withContext(NonCancellable + ioDispatcher) {
                mediaStore.save(stored.assetId, stored.mimeType, imageBytes)
                mediaSaved = true
            }
            dao.insert(stored.toEntity())
        } catch (failure: Exception) {
            // Delete ciphertext only after the database confirms that no metadata row
            // committed. A failed read-back is an unknown state, so preserve the file.
            val metadataAbsent = try {
                withContext(NonCancellable) {
                    dao.get(stored.assetId) == null
                }
            } catch (lookupFailure: Exception) {
                failure.addSuppressed(lookupFailure)
                false
            }
            if (mediaSaved && metadataAbsent) {
                runCatching {
                    withContext(NonCancellable + ioDispatcher) {
                        mediaStore.delete(stored.assetId)
                    }
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
        stored
    }

    override suspend fun getForRequest(requestId: String): StoredGeneratedImage? {
        require(requestId.isNotBlank()) { "Generation request ID must not be blank" }
        return dao.getForRequest(requestId)?.toDomain()
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
        val bytes = withContext(ioDispatcher) {
            mediaStore.read(assetId, metadata.mimeType)
        }
        return validateDecryptedGeneratedImageBytes(bytes, metadata.byteSize, metadata.sha256)
    }

    override suspend fun delete(assetId: String) {
        requireValidAssetId(assetId)
        withContext(NonCancellable) {
            val existing = dao.get(assetId)
                ?: throw IllegalArgumentException("Generated image metadata was not found")
            val removed = dao.delete(assetId)
            check(removed == 1) { "Generated image metadata was not found" }
            try {
                withContext(ioDispatcher) {
                    mediaStore.delete(assetId)
                }
            } catch (failure: Exception) {
                // If ciphertext removal fails, restore its metadata so the image remains
                // discoverable and can be opened or deleted again after the filesystem recovers.
                runCatching { dao.insert(existing) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    private fun requireValidAssetId(assetId: String) {
        require(ASSET_ID_PATTERN.matches(assetId)) { "Generated image asset ID is invalid" }
    }

    private companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        val ASSET_ID_PATTERN = Regex("[a-fA-F0-9-]{36}")
    }
}
