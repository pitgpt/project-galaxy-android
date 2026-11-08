package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.core.usecase.PersistGeneratedImageResult

/**
 * Application-scoped ComfyUI runtime components for one configured workflow endpoint.
 * Job correlation and image persistence share the same provider instance.
 */
data class ComfyUiRuntime(
    val provider: ComfyUiImageGenerationProvider,
    val persistCompletedResult: PersistGeneratedImageResult
)

/**
 * Composes the provider with the injected job store and encrypted image repository.
 * Production callers should supply RoomComfyUiJobStore backed by GalaxyDatabaseFactory's
 * SQLCipher database; tests can inject an isolated store and transport.
 */
class ComfyUiRuntimeFactory(
    private val jobs: ComfyUiJobStore,
    private val images: GeneratedImageRepository,
    private val client: ComfyUiApiClient = ComfyUiApiClient()
) {
    fun create(
        config: ComfyUiConnectionConfig,
        template: ComfyUiWorkflowTemplate,
        clientId: String
    ): ComfyUiRuntime {
        val provider = ComfyUiImageGenerationProvider(
            config = config,
            template = template,
            clientId = clientId,
            client = client,
            jobs = jobs
        )
        return ComfyUiRuntime(
            provider = provider,
            persistCompletedResult = PersistGeneratedImageResult(provider, images)
        )
    }
}
