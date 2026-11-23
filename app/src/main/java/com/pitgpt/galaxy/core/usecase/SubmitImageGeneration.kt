package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider

/** Validates a request against the selected engine before submitting any work. */
class SubmitImageGeneration(
    private val provider: ImageGenerationProvider
) {
    suspend operator fun invoke(request: ImageGenerationRequest): String {
        val capabilities = provider.capabilities()
        require(capabilities.supports(request)) {
            "The selected image provider does not support this request"
        }
        return provider.submit(request).also { providerJobId ->
            check(providerJobId.isNotBlank()) {
                "Image provider returned an invalid job identifier"
            }
        }
    }
}
