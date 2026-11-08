package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider

/** Retrieves result metadata only after completion and validates it against the originating request. */
class GetGeneratedImageAsset(
    private val provider: ImageGenerationProvider
) {
    suspend operator fun invoke(
        providerJobId: String,
        expectedRequestId: String
    ): GeneratedImageAsset {
        require(providerJobId.isNotBlank()) { "Provider job ID must not be blank" }
        require(expectedRequestId.isNotBlank()) { "Request ID must not be blank" }

        check(provider.status(providerJobId).state == ImageGenerationState.COMPLETED) {
            "Image generation has not completed"
        }

        val asset = provider.result(providerJobId)
        check(asset.requestId == expectedRequestId) {
            "Image provider returned an asset for a different request"
        }

        val capabilities = provider.capabilities()
        check(asset.mimeType in capabilities.supportedMimeTypes) {
            "Image provider returned an unsupported image format"
        }
        check(asset.width in capabilities.minWidth..capabilities.maxWidth &&
            asset.height in capabilities.minHeight..capabilities.maxHeight) {
            "Image provider returned unsupported image dimensions"
        }

        return asset
    }
}
