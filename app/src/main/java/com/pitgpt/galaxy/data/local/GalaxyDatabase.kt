package com.pitgpt.galaxy.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pitgpt.galaxy.data.local.dao.CharacterDao
import com.pitgpt.galaxy.data.local.dao.ComfyUiJobDao
import com.pitgpt.galaxy.data.local.dao.ConversationMessageDao
import com.pitgpt.galaxy.data.local.dao.MemoryDao
import com.pitgpt.galaxy.data.local.entity.CharacterEntity
import com.pitgpt.galaxy.data.local.entity.ComfyUiJobEntity
import com.pitgpt.galaxy.data.local.entity.ConversationMessageEntity
import com.pitgpt.galaxy.data.local.entity.MemoryEntity

@Database(
    entities = [
        CharacterEntity::class,
        ConversationMessageEntity::class,
        MemoryEntity::class,
        ComfyUiJobEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class GalaxyDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun conversationMessageDao(): ConversationMessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun comfyUiJobDao(): ComfyUiJobDao
}
