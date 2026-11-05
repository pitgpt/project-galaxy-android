package com.pitgpt.galaxy

import android.app.Application
import com.pitgpt.galaxy.core.repository.CharacterRepository
import com.pitgpt.galaxy.core.repository.ConversationRepository
import com.pitgpt.galaxy.core.repository.MemoryRepository
import com.pitgpt.galaxy.data.local.GalaxyDatabaseFactory
import com.pitgpt.galaxy.data.repository.RoomCharacterRepository
import com.pitgpt.galaxy.data.repository.RoomConversationRepository
import com.pitgpt.galaxy.data.repository.RoomMemoryRepository

/**
 * Application-level composition root.
 *
 * Repositories share one database instance and can be injected into
 * ViewModels or use cases as the app grows.
 */
class GalaxyApplication : Application() {
    val database by lazy {
        GalaxyDatabaseFactory.get(this)
    }

    val characters: CharacterRepository by lazy {
        RoomCharacterRepository(database.characterDao())
    }

    val conversations: ConversationRepository by lazy {
        RoomConversationRepository(database.conversationMessageDao())
    }

    val memories: MemoryRepository by lazy {
        RoomMemoryRepository(database.memoryDao())
    }
}
