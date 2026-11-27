package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.core.usecase.PersistGeneratedImageResult
import com.pitgpt.galaxy.data.providers.ComfyUiSavedSettings
import com.pitgpt.galaxy.data.providers.ProviderSettings

/**
 * Application-scoped ComfyUI runtime components for one configured workflow endpoint.
 * Job correlation and image persistence share the same provider instance.
 */
data class ComfyUiRuntime(
    val provider: ComfyUiImageGenerationProvider,
    val persistCompletedResult: PersistGeneratedImageResult
)

/**
 * Raised when every saved endpoint probe fails. The message contains only route and
 * probe diagnostics; bearer tokens and full endpoint URLs are deliberately omitted.
 */
class ComfyUiEndpointUnavailableException(
    val attempts: List<ComfyUiRouteAttempt>
) : IllegalStateException(
    if (attempts.isEmpty()) {
        "No ComfyUI endpoint is configured or reachable"
    } else {
        "No configured ComfyUI endpoint is reachable: " +
            attempts.joinToString("; ") { "${it.route}: ${it.message}" }
    }
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

    /**
     * Resolves the saved LAN/VPN preference with a bounded ComfyUI probe before creating
     * a runtime. The caller supplies an explicit, validated workflow template.
     */
    suspend fun createFromSettings(
        settings: ComfyUiSavedSettings,
        template: ComfyUiWorkflowTemplate,
        clientId: String,
        selector: ComfyUiEndpointSelector = ComfyUiEndpointSelector()
    ): ComfyUiRuntime {
        val resolution = selector.resolve(settings.toEndpointOptions())
        val endpoint = resolution.selectedEndpoint
            ?: throw ComfyUiEndpointUnavailableException(resolution.attempts)
        return create(endpoint, template, clientId)
    }

    /**
     * Builds a runtime from the complete encrypted provider-settings snapshot.
     * Both connection settings and the validated workflow must exist; no default workflow
     * or endpoint is synthesized when saved configuration is incomplete.
     */
    suspend fun createFromProviderSettings(
        settings: ProviderSettings,
        clientId: String,
        selector: ComfyUiEndpointSelector = ComfyUiEndpointSelector()
    ): ComfyUiRuntime {
        val connection = settings.comfyUi
            ?: throw IllegalStateException("ComfyUI connection settings are not configured")
        val template = settings.comfyUiWorkflow
            ?: throw IllegalStateException("ComfyUI workflow template is not configured")
        return createFromSettings(connection, template, clientId, selector)
    }
}
