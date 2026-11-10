package com.pitgpt.galaxy.data.repository

import androidx.room.Room
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RoomImageGenerationRequestStoreTest {
    private lateinit var database: GalaxyDatabase
    private lateinit var store: RoomImageGenerationRequestStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            GalaxyDatabase::class.java
        ).allowMainThreadQueries().build()
        store = RoomImageGenerationRequestStore(database.imageGenerationRequestDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roundTripsRequestContextAndReturnsNewestFirst() = runBlocking {
        val older = request("request-older", "character-a", 10L)
        val newer = request("request-newer", "character-a", 20L)

        store.put(older)
        store.put(newer)

        assertEquals(older, store.get(older.id))
        assertEquals(listOf(newer, older), store.all())
        assertEquals(newer, store.latestForCharacter("character-a"))
        assertNull(store.latestForCharacter("character-missing"))
    }

    @Test
    fun allowsPromptUpdateForTheSameRequestAndCharacter() = runBlocking {
        val original = request("request-1", "character-a", 10L)
        val edited = original.copy(prompt = "Updated prompt")

        store.put(original)
        store.put(edited)

        assertEquals(edited, store.get(original.id))
    }

    @Test
    fun rejectsRequestIdReuseAcrossDifferentGalaxyContext() = runBlocking {
        val original = request("request-1", "character-a", 10L)
        store.put(original)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { store.put(request("request-1", "character-b", 20L)) }
        }

        assertTrue(failure.message.orEmpty().contains("different Galaxy context"))
        assertEquals(original, store.get(original.id))
    }

    @Test
    fun concurrentRequestIdReuseCannotOverwriteDifferentGalaxyContext() = runBlocking {
        val first = request("request-race", "character-a", 10L)
        val second = request("request-race", "character-b", 20L)

        val outcomes = listOf(
            async(Dispatchers.IO) { runCatching { store.put(first) } },
            async(Dispatchers.IO) { runCatching { store.put(second) } }
        ).awaitAll()

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        val conflict = outcomes.single { it.isFailure }.exceptionOrNull()
        assertTrue(conflict is IllegalStateException)
        assertTrue(conflict?.message.orEmpty().contains("different Galaxy context"))

        val persisted = requireNotNull(store.get(first.id))
        assertTrue(persisted == first || persisted == second)
    }

    @Test
    fun deleteRemovesOnlyTheSelectedRequest() = runBlocking {
        val first = request("request-1", "character-a", 10L)
        val second = request("request-2", "character-a", 20L)
        store.put(first)
        store.put(second)

        store.delete(first.id)

        assertNull(store.get(first.id))
        assertEquals(listOf(second), store.all())
    }

    @Test
    fun requestAndEntityStringRepresentationsRedactPromptText() = runBlocking {
        val saved = request("request-private", "character-a", 10L)
        store.put(saved)
        val entity = database.imageGenerationRequestDao().get(saved.id)

        assertFalse(saved.toString().contains(saved.prompt))
        assertFalse(entity.toString().contains(saved.prompt))
        assertTrue(saved.toString().contains("<redacted>"))
        assertTrue(entity.toString().contains("<redacted>"))
    }

    private fun request(id: String, characterId: String, createdAt: Long) =
        ImageGenerationRequest(
            id = id,
            conversationId = characterId,
            characterId = characterId,
            prompt = "Sensitive prompt $id",
            negativePrompt = "Sensitive negative prompt",
            preferredMimeType = "image/png",
            workflowId = "workflow-v1",
            width = 768,
            height = 1024,
            seed = 42L,
            referenceAssetIds = listOf("asset-a", "asset-b"),
            createdAtEpochMillis = createdAt
        )
}
