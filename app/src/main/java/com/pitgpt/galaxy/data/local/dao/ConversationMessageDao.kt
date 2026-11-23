package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pitgpt.galaxy.data.local.entity.ConversationMessageEntity

@Dao
interface ConversationMessageDao {
    @Query("SELECT * FROM conversation_messages WHERE characterId = :characterId ORDER BY createdAtEpochMillis ASC, id ASC")
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
    suspend fun commitPendingTurn(
        pendingMessageId: String,
        userMessage: ConversationMessageEntity,
        assistantMessage: ConversationMessageEntity
    ) {
        deleteById(pendingMessageId)
        insertTurn(userMessage, assistantMessage)
    }

    @Query("DELETE FROM conversation_messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

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
