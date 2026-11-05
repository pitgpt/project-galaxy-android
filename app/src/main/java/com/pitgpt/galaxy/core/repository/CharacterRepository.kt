package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.CharacterProfile

/** Storage boundary for built-in and user-created character profiles. */
interface CharacterRepository {
    suspend fun getAll(): List<CharacterProfile>
    suspend fun getById(id: String): CharacterProfile?
    suspend fun save(profile: CharacterProfile)
    suspend fun delete(id: String)
}
