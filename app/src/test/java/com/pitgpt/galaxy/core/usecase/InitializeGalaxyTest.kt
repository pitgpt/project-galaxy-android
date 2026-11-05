package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.CharacterProfile
import com.pitgpt.galaxy.core.model.DefaultCharacter
import com.pitgpt.galaxy.core.repository.CharacterRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InitializeGalaxyTest {
    @Test
    fun firstInitializationInstallsBuiltInCharacter() = runBlocking {
        val repository = FakeCharacterRepository()
        InitializeGalaxy(repository).invoke()
        assertEquals(DefaultCharacter.profile, repository.getById("galaxy.default"))
        assertEquals(1, repository.savedProfiles.size)
    }

    @Test
    fun repeatedInitializationDoesNotOverwriteExistingProfile() = runBlocking {
        val repository = FakeCharacterRepository()
        val customized = DefaultCharacter.profile.copy(
            displayName = "Моя Галактика",
            description = "Пользовательская настройка"
        )
        repository.save(customized)
        InitializeGalaxy(repository).invoke()
        assertEquals(customized, repository.getById(customized.id))
        assertEquals(1, repository.savedProfiles.size)
    }

    @Test
    fun builtInProfileHasStableIdentity() {
        val profile = DefaultCharacter.profile
        assertEquals("galaxy.default", profile.id)
        assertEquals("Галактика", profile.displayName)
        assertNotNull(profile.personaDefinition)
        assertEquals(true, profile.isBuiltIn)
    }

    private class FakeCharacterRepository : CharacterRepository {
        val savedProfiles = mutableListOf<CharacterProfile>()
        override suspend fun getAll(): List<CharacterProfile> = savedProfiles.toList()
        override suspend fun getById(id: String): CharacterProfile? =
            savedProfiles.firstOrNull { it.id == id }
        override suspend fun save(profile: CharacterProfile) {
            savedProfiles.removeAll { it.id == profile.id }
            savedProfiles += profile
        }
        override suspend fun delete(id: String) {
            savedProfiles.removeAll { it.id == id }
        }
    }
}
