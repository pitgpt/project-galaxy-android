package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider

/** Cancels an active job only when the selected engine advertises cancellation support. */
class CancelImageGeneration(
    private val provider: ImageGenerationProvider
) {
    suspend operator fun invoke(providerJobId: String) {
        require(providerJobId.isNotBlank()) { "Provider job ID must not be blank" }

        val capabilities = provider.capabilities()
        check(capabilities.supportsCancellation) {
            "The selected image provider does not support cancellation"
        }

        val current = provider.status(providerJobId)
        check(current.state == ImageGenerationState.QUEUED ||
            current.state == ImageGenerationState.RUNNING) {
            "Only queued or running image generation jobs can be cancelled"
        }

        provider.cancel(providerJobId)
    }
}
