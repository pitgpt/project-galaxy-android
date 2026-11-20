package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pitgpt.galaxy.data.local.entity.ConversationMessageEntity

@Dao
interface ConversationMessageDao {
    @Query("SELECT * FROM conversation_messages WHERE characterId = :characterId ORDER BY createdAtEpochMillis")
    suspend fun getForCharacter(characterId: String): List<ConversationMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversationMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ConversationMessageEntity>)

    @Transaction
    suspend fun insertTurn(userMessage: ConversationMessageEntity, assistantMessage: ConversationMessageEntity) {
        insert(userMessage)
        insert(assistantMessage)
    }

    @Transaction
    suspend fun replaceForCharacter(
        characterId: String,
        entities: List<ConversationMessageEntity>
    ) {
        require(entities.all { it.characterId == characterId })
        deleteForCharacter(characterId)
        if (entities.isNotEmpty()) insertAll(entities)
    }

    @Query("DELETE FROM conversation_messages WHERE characterId = :characterId")
    suspend fun deleteForCharacter(characterId: String)
}
