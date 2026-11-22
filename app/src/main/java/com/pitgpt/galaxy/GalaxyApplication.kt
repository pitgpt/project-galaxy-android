package com.pitgpt.galaxy

import android.app.Application
import com.pitgpt.galaxy.core.repository.CharacterRepository
import com.pitgpt.galaxy.core.repository.ConversationRepository
import com.pitgpt.galaxy.core.repository.MemoryRepository
import com.pitgpt.galaxy.core.usecase.InitializeGalaxy
import com.pitgpt.galaxy.data.auth.EncryptedGalaxyBackendConfigStore
import com.pitgpt.galaxy.data.auth.GalaxyBackendConfiguration
import com.pitgpt.galaxy.data.auth.SupabaseAuthService
import com.pitgpt.galaxy.data.auth.SupabaseClientFactory
import com.pitgpt.galaxy.data.local.GalaxyDatabaseFactory
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import com.pitgpt.galaxy.data.remote.GalaxyAccessTokenProvider
import com.pitgpt.galaxy.data.remote.HttpLilaTurnProvider
import com.pitgpt.galaxy.data.remote.HttpLilaProviderCatalog
import com.pitgpt.galaxy.data.remote.HttpRealtimeSessionProvider
import com.pitgpt.galaxy.data.repository.RoomCharacterRepository
import com.pitgpt.galaxy.data.repository.RoomConversationRepository
import com.pitgpt.galaxy.data.repository.RoomMemoryRepository

/** Application-level composition root for Galaxy's local data and authentication layers. */
class GalaxyApplication : Application() {
    val database by lazy { GalaxyDatabaseFactory.get(this) }
    val characters: CharacterRepository by lazy { RoomCharacterRepository(database.characterDao()) }
    val conversations: ConversationRepository by lazy { RoomConversationRepository(database.conversationMessageDao()) }
    val memories: MemoryRepository by lazy { RoomMemoryRepository(database.memoryDao()) }
    /**
     * Device-bound app-private media storage. Binary assets are AES-GCM ciphertext;
     * image metadata remains a separate Room concern.
     */
    val mediaStore: EncryptedMediaStore by lazy { EncryptedMediaStore.fromContext(this) }
    private lateinit var backendConfigStore: EncryptedGalaxyBackendConfigStore
    private var activeBackendConfiguration: GalaxyBackendConfiguration? = null

    @Volatile
    private var currentAuth: SupabaseAuthService? = null

    @Volatile
    private var currentLilaTurnProvider: HttpLilaTurnProvider? = null

    @Volatile
    private var currentLilaProviderCatalog: HttpLilaProviderCatalog? = null

    private val initializeGalaxy by lazy { InitializeGalaxy(characters) }

    val auth: SupabaseAuthService?
        get() = currentAuth

    val lilaTurnProvider: HttpLilaTurnProvider
        get() = checkNotNull(currentLilaTurnProvider) { "Galaxy application has not been initialized." }

    val lilaProviderCatalog: HttpLilaProviderCatalog
        get() = checkNotNull(currentLilaProviderCatalog) { "Galaxy application has not been initialized." }

    val hasBackendConfiguration: Boolean
        get() = activeBackendConfiguration != null

    val backendConfigurationRecoveryRequired: Boolean
        get() = backendConfigStore.hasStoredConfiguration() &&
            activeBackendConfiguration == null &&
            GalaxyBackendConfiguration.fromBuildConfig() == null

    override fun onCreate() {
        super.onCreate()
        backendConfigStore = EncryptedGalaxyBackendConfigStore(this)
        activeBackendConfiguration = backendConfigStore.load()
            ?: GalaxyBackendConfiguration.fromBuildConfig()
        rebuildRemoteClients()
    }

    @Synchronized
    fun saveBackendConfiguration(configuration: GalaxyBackendConfiguration) {
        val validated = configuration.validated()
        backendConfigStore.save(validated)
        activeBackendConfiguration = validated
        rebuildRemoteClients()
    }

    @Synchronized
    fun resetBackendConfiguration() {
        backendConfigStore.clear()
        activeBackendConfiguration = GalaxyBackendConfiguration.fromBuildConfig()
        rebuildRemoteClients()
    }

    private fun rebuildRemoteClients() {
        val configuration = activeBackendConfiguration
        currentAuth = SupabaseClientFactory.createOrNull(this, configuration)
        val apiBaseUrl = configuration?.galaxyApiBaseUrl ?: BuildConfig.GALAXY_API_BASE_URL
        currentLilaTurnProvider = HttpLilaTurnProvider(apiBaseUrl, currentAuth)
        currentLilaProviderCatalog = HttpLilaProviderCatalog(apiBaseUrl, currentAuth)
    }

    fun realtimeSessionProvider(tokenProvider: GalaxyAccessTokenProvider): HttpRealtimeSessionProvider {
        val apiBaseUrl = activeBackendConfiguration?.galaxyApiBaseUrl
            ?: BuildConfig.GALAXY_API_BASE_URL
        return HttpRealtimeSessionProvider(apiBaseUrl, tokenProvider)
    }

    fun authenticatedRealtimeSessionProvider(): HttpRealtimeSessionProvider {
        val authService = requireNotNull(auth) { "Supabase project configuration is missing" }
        return realtimeSessionProvider(authService)
    }

    suspend fun initialize() {
        initializeGalaxy()
        auth?.initialize()
    }
}
