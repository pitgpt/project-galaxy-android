package com.pitgpt.galaxy.data.repository

import androidx.room.Room
import com.pitgpt.galaxy.core.model.CharacterProfile
import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MemoryItem
import com.pitgpt.galaxy.core.model.MemoryScope
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.model.DefaultCharacter
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RoomRepositoriesTest {
    private lateinit var database: GalaxyDatabase
    private lateinit var characters: RoomCharacterRepository
    private lateinit var conversations: RoomConversationRepository
    private lateinit var memories: RoomMemoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            GalaxyDatabase::class.java
        ).allowMainThreadQueries().build()
        characters = RoomCharacterRepository(database.characterDao())
        conversations = RoomConversationRepository(database.conversationMessageDao())
        memories = RoomMemoryRepository(database.memoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun characterProfilesRoundTripAndBuiltInCannotBeDeleted() = runBlocking {
        val builtIn = DefaultCharacter.profile
        val custom = CharacterProfile(
            id = "custom.one",
            displayName = "Nova",
            description = "Test character",
            personaDefinition = "Be concise"
        )

        characters.save(builtIn)
        characters.save(custom)
        assertEquals(builtIn, characters.getById(builtIn.id))
        assertEquals(custom, characters.getById(custom.id))

        characters.delete(builtIn.id)
        characters.delete(custom.id)

        assertEquals(builtIn, characters.getById(builtIn.id))
        assertNull(characters.getById(custom.id))
    }

    @Test
    fun conversationHistoryIsIsolatedAndCanBeClearedPerCharacter() = runBlocking {
        val first = ConversationMessage("m1", "galaxy.default", MessageRole.USER, "Hello", 10L)
        val second = ConversationMessage("m2", "nova", MessageRole.CHARACTER, "Hi", 20L)
        conversations.append(first)
        conversations.append(second)

        assertEquals(listOf(first), conversations.getForCharacter("galaxy.default"))
        assertEquals(listOf(second), conversations.getForCharacter("nova"))

        conversations.clearForCharacter("galaxy.default")

        assertTrue(conversations.getForCharacter("galaxy.default").isEmpty())
        assertEquals(listOf(second), conversations.getForCharacter("nova"))
    }

    @Test
    fun memoryIsIsolatedByScopeAndCharacterOwner() = runBlocking {
        val userMemory = MemoryItem(
            id = "user-1", scope = MemoryScope.USER, content = "User preference",
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L
        )
        val novaMemory = MemoryItem(
            id = "nova-1", scope = MemoryScope.CHARACTER, ownerCharacterId = "nova",
            content = "Nova memory", createdAtEpochMillis = 2L, updatedAtEpochMillis = 2L
        )
        val galaxyMemory = MemoryItem(
            id = "galaxy-1", scope = MemoryScope.CHARACTER, ownerCharacterId = "galaxy.default",
            content = "Galaxy memory", createdAtEpochMillis = 3L, updatedAtEpochMillis = 3L
        )
        memories.save(userMemory)
        memories.save(novaMemory)
        memories.save(galaxyMemory)

        assertEquals(listOf(userMemory), memories.getAll(MemoryScope.USER))
        assertEquals(listOf(novaMemory), memories.getAll(MemoryScope.CHARACTER, "nova"))
        assertEquals(listOf(galaxyMemory), memories.getAll(MemoryScope.CHARACTER, "galaxy.default"))
    }

    @Test
    fun deletingOneMemoryDoesNotDeleteOtherScopes() = runBlocking {
        val first = MemoryItem(
            id = "one", scope = MemoryScope.SHARED, content = "Shared",
            createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L
        )
        val second = MemoryItem(
            id = "two", scope = MemoryScope.USER, content = "Private",
            createdAtEpochMillis = 2L, updatedAtEpochMillis = 2L
        )
        memories.save(first)
        memories.save(second)

        memories.deleteAll(MemoryScope.SHARED)

        assertTrue(memories.getAll(MemoryScope.SHARED).isEmpty())
        assertEquals(listOf(second), memories.getAll(MemoryScope.USER))
    }
}
