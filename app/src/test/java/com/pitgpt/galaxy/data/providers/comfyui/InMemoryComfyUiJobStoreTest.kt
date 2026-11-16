package com.pitgpt.galaxy.data.providers.comfyui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryComfyUiJobStoreTest {
    @Test
    fun cancelledPreparedJobCannotAcquireSubmissionClaim() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-cancelled-prepared",
            requestId = "request-cancelled-prepared",
            createdAtEpochMillis = 1_794_230_800_000L,
            cancelled = true
        )
        val store = InMemoryComfyUiJobStore()
        store.put(record)

        assertFalse(store.markSubmissionStarted(record.promptId))
        assertEquals(record, store.get(record.promptId))
    }

    @Test
    fun preparedJobCannotBeMarkedCancelled() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-prepared",
            requestId = "request-prepared",
            createdAtEpochMillis = 1_794_230_800_001L
        )
        val store = InMemoryComfyUiJobStore()
        store.put(record)

        val failure = runCatching { store.markCancelled(record.promptId) }.exceptionOrNull()

        assertTrue("A PREPARED job must not transition directly to CANCELLED", failure is IllegalStateException)
        assertEquals(record, store.get(record.promptId))
    }

    @Test
    fun submittedJobCanBeCancelledRepeatedly() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-submitted",
            requestId = "request-submitted",
            createdAtEpochMillis = 1_794_230_800_002L
        )
        val store = InMemoryComfyUiJobStore()
        store.put(record)

        assertTrue(store.markSubmissionStarted(record.promptId))
        store.markCancelled(record.promptId)
        store.markCancelled(record.promptId)

        assertEquals(
            record.copy(submissionStarted = true, cancelled = true),
            store.get(record.promptId)
        )
    }

    @Test
    fun missingJobCannotBeMarkedCancelled() = runBlocking {
        val store = InMemoryComfyUiJobStore()

        val failure = runCatching { store.markCancelled("missing-prompt") }.exceptionOrNull()

        assertTrue("An unknown job must not be reported as cancelled", failure is IllegalStateException)
    }
}
