package com.pitgpt.galaxy.data.providers.comfyui

import androidx.room.Room
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RoomComfyUiJobStoreTest {
    private lateinit var database: GalaxyDatabase
    private lateinit var store: RoomComfyUiJobStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, GalaxyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomComfyUiJobStore(database.comfyUiJobDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun storesAndReadsOnlyJobCorrelation() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-123",
            requestId = "request-456",
            createdAtEpochMillis = 1234L
        )

        store.put(record)

        assertEquals(record, store.get("prompt-123"))
        assertNull(store.get("unknown-prompt"))
    }

    @Test
    fun cancellationFlagIsPersisted() = runBlocking {
        store.put(ComfyUiJobRecord("prompt-123", "request-456", 1234L))

        store.markCancelled("prompt-123")

        val saved = store.get("prompt-123")
        assertTrue(saved!!.cancelled)
        assertEquals("request-456", saved.requestId)
    }

    @Test
    fun duplicatePromptIdIsRejectedRatherThanOverwritten() = runBlocking {
        store.put(ComfyUiJobRecord("prompt-123", "request-456", 1234L))

        val failure = runCatching {
            store.put(ComfyUiJobRecord("prompt-123", "request-other", 9999L))
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertFalse(store.get("prompt-123")!!.requestId == "request-other")
    }
}
