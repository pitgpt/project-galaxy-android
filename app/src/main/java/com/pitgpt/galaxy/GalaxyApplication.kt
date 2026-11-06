package com.pitgpt.galaxy

import android.app.Application
import com.pitgpt.galaxy.core.repository.CharacterRepository
import com.pitgpt.galaxy.core.repository.ConversationRepository
import com.pitgpt.galaxy.core.repository.MemoryRepository
import com.pitgpt.galaxy.core.usecase.InitializeGalaxy
import com.pitgpt.galaxy.data.auth.SupabaseAuthService
import com.pitgpt.galaxy.data.auth.SupabaseClientFactory
import com.pitgpt.galaxy.data.local.GalaxyDatabaseFactory
import com.pitgpt.galaxy.data.remote.GalaxyAccessTokenProvider
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
    val auth: SupabaseAuthService? by lazy { SupabaseClientFactory.createOrNull(this) }
    private val initializeGalaxy by lazy { InitializeGalaxy(characters) }

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
