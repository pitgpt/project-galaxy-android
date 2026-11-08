package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ImageGenerationStatus
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider

/** Retrieves the current provider state for a previously submitted generation job. */
class GetImageGenerationStatus(
    private val provider: ImageGenerationProvider
) {
    suspend operator fun invoke(providerJobId: String): ImageGenerationStatus {
        require(providerJobId.isNotBlank()) { "Provider job ID must not be blank" }
        return provider.status(providerJobId)
    }
}
