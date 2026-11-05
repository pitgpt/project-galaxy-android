package com.pitgpt.galaxy.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pitgpt.galaxy.data.local.entity.CharacterEntity

@Dao
interface CharacterDao {
    @Query("SELECT * FROM character_profiles ORDER BY displayName COLLATE NOCASE")
    suspend fun getAll(): List<CharacterEntity>

    @Query("SELECT * FROM character_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CharacterEntity?

    @Upsert
    suspend fun upsert(entity: CharacterEntity)

    @Query("DELETE FROM character_profiles WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteUserCharacter(id: String)
}
