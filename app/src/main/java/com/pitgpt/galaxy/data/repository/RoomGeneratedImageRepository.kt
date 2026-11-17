package com.pitgpt.galaxy.data.repository

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageReadException
import com.pitgpt.galaxy.core.repository.GeneratedImageReadFailure
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.data.local.dao.GeneratedImageDao
import com.pitgpt.galaxy.data.local.entity.toDomain
import com.pitgpt.galaxy.data.local.entity.toEntity
import com.pitgpt.galaxy.data.media.EncryptedMediaAssetMissingException
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import com.pitgpt.galaxy.data.media.MediaEncryptionKeyUnavailableException
import java.io.EOFException
import java.security.InvalidKeyException
import java.security.InvalidAlgorithmParameterException
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
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
        val metadata = try {
            dao.get(assetId)?.toDomain()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.INTEGRITY_MISMATCH, failure)
        } catch (failure: Exception) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.STORAGE_UNAVAILABLE, failure)
        } ?: throw GeneratedImageReadException(GeneratedImageReadFailure.METADATA_MISSING)

        val bytes = try {
            withContext(ioDispatcher) {
                mediaStore.read(assetId, metadata.mimeType)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MediaEncryptionKeyUnavailableException) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.KEY_UNAVAILABLE, failure)
        } catch (failure: InvalidKeyException) {
            // Android Keystore can return a key object that is no longer usable, for example
            // after permanent invalidation. Treat it as unavailable, never as corrupt media.
            throw GeneratedImageReadException(GeneratedImageReadFailure.KEY_UNAVAILABLE, failure)
        } catch (failure: InvalidAlgorithmParameterException) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.CORRUPT_CIPHERTEXT, failure)
        } catch (failure: EncryptedMediaAssetMissingException) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.CIPHERTEXT_MISSING, failure)
        } catch (failure: AEADBadTagException) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.CORRUPT_CIPHERTEXT, failure)
        } catch (failure: EOFException) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.CORRUPT_CIPHERTEXT, failure)
        } catch (failure: IllegalArgumentException) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.CORRUPT_CIPHERTEXT, failure)
        } catch (failure: Exception) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.STORAGE_UNAVAILABLE, failure)
        }

        return try {
            validateDecryptedGeneratedImageBytes(bytes, metadata.byteSize, metadata.sha256)
        } catch (failure: Exception) {
            throw GeneratedImageReadException(GeneratedImageReadFailure.INTEGRITY_MISMATCH, failure)
        }
    }

    override suspend fun delete(assetId: String) {
        requireValidAssetId(assetId)
        val removed = dao.delete(assetId)
        check(removed == 1) { "Generated image metadata was not found" }
        withContext(NonCancellable + ioDispatcher) {
            mediaStore.delete(assetId)
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
