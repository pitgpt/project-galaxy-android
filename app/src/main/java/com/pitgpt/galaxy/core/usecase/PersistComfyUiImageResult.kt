package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiImageResultSource

/**
 * Transfers one completed ComfyUI result from bounded memory into encrypted media storage.
 * The repository validates request correlation, MIME type, byte count and checksum again.
 */
class PersistComfyUiImageResult(
    private val resultSource: ComfyUiImageResultSource,
    private val images: GeneratedImageRepository
) {
    suspend operator fun invoke(
        request: ImageGenerationRequest,
        providerJobId: String
    ): StoredGeneratedImage {
        require(providerJobId.isNotBlank()) { "Provider job ID must not be blank" }
        val result = resultSource.completedImage(providerJobId)
        check(result.asset.requestId == request.id) {
            "ComfyUI returned an image for a different generation request"
        }
        check(result.asset.mimeType == result.download.mimeType) {
            "ComfyUI image MIME metadata does not match the downloaded bytes"
        }
        check(result.asset.byteSize == result.download.byteSize) {
            "ComfyUI image size metadata does not match the downloaded bytes"
        }
        check(result.asset.sha256.equals(result.download.sha256, ignoreCase = true)) {
            "ComfyUI image checksum metadata does not match the downloaded bytes"
        }
        return images.save(request, result.asset, result.download.bytes)
    }
}
