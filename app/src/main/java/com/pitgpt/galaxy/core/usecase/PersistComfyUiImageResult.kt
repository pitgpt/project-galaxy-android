package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.core.repository.GeneratedImageResultSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transfers one completed provider result from bounded memory into encrypted media storage.
 * The repository validates request correlation, MIME type, byte count and checksum again.
 */
class PersistGeneratedImageResult(
    private val resultSource: GeneratedImageResultSource,
    private val images: GeneratedImageRepository
) {
    private val persistenceMutex = Mutex()

    suspend operator fun invoke(
        request: ImageGenerationRequest,
        providerJobId: String
    ): StoredGeneratedImage {
        return persistenceMutex.withLock {
            require(providerJobId.isNotBlank()) { "Provider job ID must not be blank" }
            val existing = images.getForRequest(request.id)
            if (existing != null) {
                check(
                    existing.conversationId == request.conversationId &&
                        existing.characterId == request.characterId
                ) { "The generation request ID is already associated with different Galaxy context" }
                existing
            } else {
                val result = resultSource.completedImage(providerJobId)
                try {
                    check(result.asset.requestId == request.id) {
                        "Image provider returned an image for a different generation request"
                    }
                    check(result.asset.mimeType == result.mimeType) {
                        "Image MIME metadata does not match the downloaded bytes"
                    }
                    check(result.asset.byteSize == result.byteSize) {
                        "Image size metadata does not match the downloaded bytes"
                    }
                    check(result.asset.sha256.equals(result.sha256, ignoreCase = true)) {
                        "Image checksum metadata does not match the downloaded bytes"
                    }
                    images.save(request, result.asset, result.bytes)
                } finally {
                    // The encrypted repository has consumed the payload; retire the plaintext buffer.
                    result.bytes.fill(0)
                }
            }
        }
    }
}
