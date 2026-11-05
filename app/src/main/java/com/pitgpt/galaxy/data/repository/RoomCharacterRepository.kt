package com.pitgpt.galaxy.data.repository

import com.pitgpt.galaxy.core.model.CharacterProfile
import com.pitgpt.galaxy.core.repository.CharacterRepository
import com.pitgpt.galaxy.data.local.dao.CharacterDao
import com.pitgpt.galaxy.data.local.entity.toDomain
import com.pitgpt.galaxy.data.local.entity.toEntity

class RoomCharacterRepository(
    private val dao: CharacterDao
) : CharacterRepository {
    override suspend fun getAll(): List<CharacterProfile> =
        dao.getAll().map { it.toDomain() }

    override suspend fun getById(id: String): CharacterProfile? =
        dao.getById(id)?.toDomain()

    override suspend fun save(profile: CharacterProfile) {
        dao.upsert(profile.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.deleteUserCharacter(id)
    }
}
