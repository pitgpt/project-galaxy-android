package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.repository.ImageGenerationRequestStore
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.InMemoryComfyUiJobStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationPanelTest {
    @Test
    fun navigationIsLockedOnlyDuringSubmissionBecauseActiveJobsAreDurable() {
        assertTrue(imageGenerationNavigationLocked(isSubmitting = true))
        assertFalse(imageGenerationNavigationLocked(isSubmitting = false))
    }

    @Test
    fun restoresTheNewestRequestForTheSelectedCharacter() {
        val older = request("older", "character-a", 10L)
        val newestOtherCharacter = request("other", "character-b", 30L)
        val newestSelectedCharacter = request("newest", "character-a", 20L)

        val restored = latestRecoverableImageGenerationRequest(
            characterId = "character-a",
            requests = listOf(older, newestOtherCharacter, newestSelectedCharacter),
            jobs = emptyList()
        )

        assertEquals(newestSelectedCharacter, restored?.request)
        assertNull(restored?.job)
        assertTrue(restored?.canResumeSafely == true)
    }

    @Test
    fun restoresSavedRequestWithoutWaitingForNetworkRuntime() = runBlocking {
        val saved = request("offline-recovery", "character-a", 15L)
        val requestStore = object : ImageGenerationRequestStore {
            override suspend fun put(request: ImageGenerationRequest) = Unit
            override suspend fun get(requestId: String): ImageGenerationRequest? =
                saved.takeIf { it.id == requestId }
            override suspend fun all(): List<ImageGenerationRequest> = listOf(saved)
            override suspend fun latestForCharacter(characterId: String): ImageGenerationRequest? =
                saved.takeIf { it.characterId == characterId }
            override suspend fun delete(requestId: String) = Unit
        }

        // This recovery lookup has no runtime or network dependency, so local state
        // remains available even when the saved ComfyUI endpoint cannot be reached.
        val restored = loadLatestRecoverableImageGenerationRequest(
            characterId = "character-a",
            requestStore = requestStore,
            jobStore = InMemoryComfyUiJobStore()
        )

        assertEquals(saved, restored?.request)
        assertNull(restored?.job)
        assertTrue(restored?.canResumeSafely == true)
    }

    @Test
    fun preparedOrOrphanRequestCanBeResumedSafely() {
        val saved = request("prepared", "character-a", 10L)
        val restored = latestRecoverableImageGenerationRequest(
            characterId = "character-a",
            requests = listOf(saved),
            jobs = listOf(ComfyUiJobRecord("prompt-prepared", saved.id, saved.createdAtEpochMillis))
        )

        assertTrue(restored?.canResumeSafely == true)
    }

    @Test
    fun startedRequestIsNotClassifiedAsSafeToResubmit() {
        val saved = request("started", "character-a", 10L)
        val restored = latestRecoverableImageGenerationRequest(
            characterId = "character-a",
            requests = listOf(saved),
            jobs = listOf(
                ComfyUiJobRecord(
                    promptId = "prompt-started",
                    requestId = saved.id,
                    createdAtEpochMillis = saved.createdAtEpochMillis,
                    submissionStarted = true
                )
            )
        )

        assertFalse(restored?.canResumeSafely ?: true)
        assertEquals("prompt-started", restored?.job?.promptId)
    }

    private fun request(id: String, characterId: String, createdAt: Long) =
        ImageGenerationRequest(
            id = id,
            conversationId = characterId,
            characterId = characterId,
            prompt = "private prompt",
            createdAtEpochMillis = createdAt
        )
}
