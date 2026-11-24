package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.repository.ImageGenerationRequestStore
import com.pitgpt.galaxy.data.remote.LilaImageHistoryJob
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.InMemoryComfyUiJobStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationPanelTest {
    @Test
    fun historySyncWarningDistinguishesImportLimitsFromFailedJobs() {
        val limitOnly = coreImageHistorySyncWarning(
            CoreImageHistorySyncResult(importedImages = emptyList(), skippedAssets = 2)
        )
        assertTrue(limitOnly.orEmpty().contains("лимит"))
        assertFalse(limitOnly.orEmpty().contains("Не удалось импортировать"))

        val failedOnly = coreImageHistorySyncWarning(
            CoreImageHistorySyncResult(
                importedImages = emptyList(),
                skippedAssets = 0,
                failedJobs = 1
            )
        )
        assertTrue(failedOnly.orEmpty().contains("Не удалось импортировать"))
        assertFalse(failedOnly.orEmpty().contains("лимит"))

        val mixed = coreImageHistorySyncWarning(
            CoreImageHistorySyncResult(
                importedImages = emptyList(),
                skippedAssets = 2,
                failedJobs = 1
            )
        )
        assertTrue(mixed.orEmpty().contains("Не удалось импортировать"))
        assertTrue(mixed.orEmpty().contains("лимит"))
        assertNull(coreImageHistorySyncWarning(
            CoreImageHistorySyncResult(importedImages = emptyList(), skippedAssets = 0)
        ))
    }

    @Test
    fun coreHistoryRestoresRequestIntoTheSameOwnerScopedConversation() {
        val job = LilaImageHistoryJob(
            jobId = "00000000-0000-0000-0000-000000000003",
            conversationId = "00000000-0000-0000-0000-000000000001",
            prompt = "portrait",
            negativePrompt = "blur",
            width = 768,
            height = 1024,
            seed = 42L,
            state = "completed",
            createdAtEpochMillis = 1234L,
            assetByteSize = 4096L
        )

        val restored = coreHistoryRequest(
            job = job,
            requestId = job.jobId,
            characterId = "character-a"
        )

        assertEquals(job.jobId, restored.id)
        assertEquals(job.conversationId, restored.conversationId)
        assertEquals("character-a", restored.characterId)
        assertEquals("portrait", restored.prompt)
        assertEquals("blur", restored.negativePrompt)
        assertEquals(768, restored.width)
        assertEquals(1024, restored.height)
        assertEquals(42L, restored.seed)
        assertEquals(1234L, restored.createdAtEpochMillis)
    }

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
    fun recoveryDoesNotLeakAnotherOwnersRequestForTheSameCharacter() {
        val olderSelected = request(
            "owner-a-old",
            "character-a",
            10L,
            conversationId = "owner-a:conversation"
        )
        val newerOtherOwner = request(
            "owner-b-new",
            "character-a",
            30L,
            conversationId = "owner-b:conversation"
        )
        val newestSelected = request(
            "owner-a-new",
            "character-a",
            20L,
            conversationId = "owner-a:conversation"
        )

        val restored = latestRecoverableImageGenerationRequest(
            characterId = "character-a",
            conversationId = "owner-a:conversation",
            requests = listOf(olderSelected, newerOtherOwner, newestSelected),
            jobs = emptyList()
        )

        assertEquals(newestSelected, restored?.request)
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


    @Test
    fun clearsDecryptedBytesWhenPreviewDispatchIsCancelled() = runBlocking {
        val plaintext = byteArrayOf(1, 2, 3, 4)
        val rejectingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                throw CancellationException("Preview dispatch was cancelled")
            }
        }

        var cancelled = false
        try {
            decodeGeneratedImageAndClearBytes(plaintext, rejectingDispatcher)
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertArrayEquals(ByteArray(plaintext.size), plaintext)
    }

    private fun request(
        id: String,
        characterId: String,
        createdAt: Long,
        conversationId: String = characterId
    ) =
        ImageGenerationRequest(
            id = id,
            conversationId = conversationId,
            characterId = characterId,
            prompt = "private prompt",
            createdAtEpochMillis = createdAt
        )
}
