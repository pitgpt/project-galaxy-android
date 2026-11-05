package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pitgpt.galaxy.data.local.entity.ConversationMessageEntity

@Dao
interface ConversationMessageDao {
    @Query("SELECT * FROM conversation_messages WHERE characterId = :characterId ORDER BY createdAtEpochMillis")
    suspend fun getForCharacter(characterId: String): List<ConversationMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationMessageEntity)

    @Query("DELETE FROM conversation_messages WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)
}
