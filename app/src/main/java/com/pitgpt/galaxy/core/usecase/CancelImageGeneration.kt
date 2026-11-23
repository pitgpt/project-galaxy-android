package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider

/** Cancels a job only when the provider supports cancellation for its current execution state. */
class CancelImageGeneration(
    private val provider: ImageGenerationProvider
) {
    suspend operator fun invoke(providerJobId: String) {
        require(providerJobId.isNotBlank()) { "Provider job ID must not be blank" }

        val current = provider.status(providerJobId)
        val capabilities = provider.capabilities()
        val supported = when (current.state) {
            ImageGenerationState.QUEUED -> capabilities.supportsQueuedCancellation
            ImageGenerationState.RUNNING -> capabilities.supportsRunningCancellation
            else -> false
        }
        check(supported) {
            when (current.state) {
                ImageGenerationState.QUEUED ->
                    "The selected image provider cannot cancel queued jobs"
                ImageGenerationState.RUNNING ->
                    "The selected image provider cannot safely cancel running jobs"
                else ->
                    "Only queued or running image generation jobs can be cancelled"
            }
        }

        provider.cancel(providerJobId)
    }
}
