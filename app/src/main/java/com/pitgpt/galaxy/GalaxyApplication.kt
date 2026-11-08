package com.pitgpt.galaxy

import android.app.Application
import com.pitgpt.galaxy.core.repository.CharacterRepository
import com.pitgpt.galaxy.core.repository.ConversationRepository
import com.pitgpt.galaxy.core.repository.MemoryRepository
import com.pitgpt.galaxy.core.usecase.InitializeGalaxy
import com.pitgpt.galaxy.data.auth.SupabaseAuthService
import com.pitgpt.galaxy.data.auth.SupabaseClientFactory
import com.pitgpt.galaxy.data.local.GalaxyDatabaseFactory
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import com.pitgpt.galaxy.data.remote.GalaxyAccessTokenProvider
import com.pitgpt.galaxy.data.remote.HttpRealtimeSessionProvider
import com.pitgpt.galaxy.data.repository.RoomCharacterRepository
import com.pitgpt.galaxy.data.repository.RoomConversationRepository
import com.pitgpt.galaxy.data.repository.RoomMemoryRepository
import com.pitgpt.galaxy.data.repository.RoomGeneratedImageRepository
import com.pitgpt.galaxy.data.providers.EncryptedProviderStore
import com.pitgpt.galaxy.data.providers.ProviderSettingsIo
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiConnectionConfig
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobStore
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRuntime
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRuntimeFactory
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import com.pitgpt.galaxy.data.providers.comfyui.RoomComfyUiJobStore

/** Application-level composition root for Galaxy's local data and authentication layers. */
class GalaxyApplication : Application() {
    val database by lazy { GalaxyDatabaseFactory.get(this) }
    val characters: CharacterRepository by lazy { RoomCharacterRepository(database.characterDao()) }
    val conversations: ConversationRepository by lazy { RoomConversationRepository(database.conversationMessageDao()) }
    val memories: MemoryRepository by lazy { RoomMemoryRepository(database.memoryDao()) }
    val auth: SupabaseAuthService? by lazy { SupabaseClientFactory.createOrNull(this) }
    val providerStore: EncryptedProviderStore by lazy { EncryptedProviderStore(this) }
    private val providerSettingsIo by lazy { ProviderSettingsIo(providerStore) }
    val mediaStore: EncryptedMediaStore by lazy { EncryptedMediaStore.fromContext(this) }
    val generatedImages by lazy { RoomGeneratedImageRepository(database.generatedImageDao(), mediaStore) }
    val comfyUiJobStore: ComfyUiJobStore by lazy { RoomComfyUiJobStore(database.comfyUiJobDao()) }
    private val comfyUiRuntimeFactory by lazy {
        ComfyUiRuntimeFactory(comfyUiJobStore, generatedImages)
    }
    private val initializeGalaxy by lazy { InitializeGalaxy(characters) }

    /**
     * Creates a ComfyUI runtime backed by SQLCipher job correlation and encrypted image storage.
     * Explicit endpoint and workflow configuration are supplied by the caller.
     */
    fun createComfyUiRuntime(
        config: ComfyUiConnectionConfig,
        template: ComfyUiWorkflowTemplate,
        clientId: String
    ): ComfyUiRuntime = comfyUiRuntimeFactory.create(config, template, clientId)

    /**
     * Loads the saved encrypted LAN/VPN settings on Dispatchers.IO, probes the preferred
     * route, and creates a runtime only when one configured endpoint is reachable.
     * The caller must still provide a reviewed, versioned workflow template.
     */
    suspend fun createComfyUiRuntimeFromSavedSettings(
        template: ComfyUiWorkflowTemplate,
        clientId: String
    ): ComfyUiRuntime {
        val settings = providerSettingsIo.load().comfyUi
            ?: throw IllegalStateException("ComfyUI connection settings are not configured")
        return comfyUiRuntimeFactory.createFromSettings(settings, template, clientId)
    }

    fun realtimeSessionProvider(tokenProvider: GalaxyAccessTokenProvider): HttpRealtimeSessionProvider =
        HttpRealtimeSessionProvider(BuildConfig.GALAXY_API_BASE_URL, tokenProvider)

    fun authenticatedRealtimeSessionProvider(): HttpRealtimeSessionProvider {
        val authService = requireNotNull(auth) { "Supabase project configuration is missing" }
        return realtimeSessionProvider(authService)
    }

    suspend fun initialize() {
        initializeGalaxy()
        auth?.initialize()
    }
}
