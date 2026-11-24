package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.data.remote.LilaHistoryMessage
import com.pitgpt.galaxy.data.remote.LilaImageHistoryJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyHomeHistoryTest {

    @Test
    fun timelineMergesMessagesImagesAndCoreJobsInChronologicalOrder() {
        val message = ConversationMessage(
            id = "message-1",
            characterId = "character-a",
            role = MessageRole.USER,
            content = "Create a portrait",
            createdAtEpochMillis = 1_000L
        )
        val queued = imageJob("job-queued", "queued", 2_000L)
        val completed = imageJob("job-completed", "completed", 3_000L)
        val failed = imageJob("job-failed", "failed", 4_000L)
        val storedImage = StoredGeneratedImage(
            assetId = "asset-completed",
            requestId = "request-completed",
            conversationId = "conversation-a",
            characterId = "character-a",
            mimeType = "image/png",
            width = 64,
            height = 64,
            byteSize = 128L,
            sha256 = "a".repeat(64),
            createdAtEpochMillis = 3_000L
        )

        val timeline = buildConversationTimeline(
            messages = listOf(message),
            images = listOf(storedImage),
            imageJobs = listOf(queued, completed, failed),
            requestIdsByJobId = mapOf("job-completed" to "request-completed")
        )

        assertEquals(
            listOf(
                "message:message-1",
                "image-job:job-queued",
                "image:asset-completed",
                "image-job:job-failed"
            ),
            timeline.map { it.key }
        )
        assertEquals("queued", timeline[1].imageJob?.state)
        assertEquals("failed", timeline[3].imageJob?.state)
    }

    @Test
    fun completedCoreJobRemainsVisibleUntilItsEncryptedAssetIsImported() {
        val completed = imageJob("job-without-local-asset", "completed", 3_000L)

        val timeline = buildConversationTimeline(
            messages = emptyList(),
            images = emptyList(),
            imageJobs = listOf(completed),
            requestIdsByJobId = emptyMap()
        )

        assertEquals(listOf("image-job:job-without-local-asset"), timeline.map { it.key })
        assertEquals("completed", timeline.single().imageJob?.state)
    }

    @Test
    fun imageHistoryPageMergeKeepsOlderJobsAndPrefersFreshState() {
        val older = imageJob("job-older", "queued", 1_000L)
        val newest = imageJob("job-newest", "completed", 3_000L)
        val refreshedOlder = older.copy(state = "running")

        val merged = mergeLilaImageHistoryJobs(
            existing = listOf(older, newest),
            incoming = listOf(refreshedOlder)
        )

        assertEquals(listOf("job-newest", "job-older"), merged.map { it.jobId })
        assertEquals("running", merged.last().state)
    }

    @Test
    fun coreImageJobPollingStopsOnlyForTerminalStates() {
        assertFalse(shouldPollCoreImageJobState("prepared"))
        assertFalse(shouldPollCoreImageJobState("submission_unknown"))
        assertTrue(shouldPollCoreImageJobState("submitting"))
        assertTrue(shouldPollCoreImageJobState("queued"))
        assertTrue(shouldPollCoreImageJobState("running"))
        assertTrue(shouldPollCoreImageJobState("result_pending"))
        assertFalse(shouldPollCoreImageJobState("completed"))
        assertFalse(shouldPollCoreImageJobState("failed"))
        assertFalse(shouldPollCoreImageJobState("cancelled"))
        assertEquals("ОЖИДАЕТ ПОДТВЕРЖДЕНИЯ", coreImageJobStatusLabel("submission_unknown"))
    }

    private fun imageJob(
        jobId: String,
        state: String,
        createdAtEpochMillis: Long
    ) = LilaImageHistoryJob(
        jobId = jobId,
        conversationId = "conversation-a",
        prompt = "silver-haired heroine portrait",
        negativePrompt = null,
        width = 768,
        height = 1024,
        seed = 42L,
        state = state,
        createdAtEpochMillis = createdAtEpochMillis,
        assetByteSize = if (state == "completed") 4096L else null
    )

    @Test
    fun mergeDoesNotInferPendingTurnCompletionFromMatchingHistoryText() {
        val characterId = "owner:partition:galaxy.default"
        val conversationId = "00000000-0000-4000-8000-000000000402"
        val pending = ConversationMessage(
            id = pendingLilaMessageId("00000000-0000-4000-8000-000000000499"),
            characterId = characterId,
            role = MessageRole.USER,
            content = "hello",
            createdAtEpochMillis = 900
        )
        val remote = listOf(
            LilaHistoryMessage(1, "user", "hello", 1000),
            LilaHistoryMessage(2, "assistant", "world", 1001)
        )

        val merged = mergeRemoteHistory(characterId, conversationId, listOf(pending), remote)

        assertEquals(
            listOf(
                pending.id,
                "lila-$conversationId-00000000000000000001",
                "lila-$conversationId-00000000000000000002"
            ),
            merged.map { it.id }
        )
        assertTrue(merged.any { it.id == pending.id })
    }

    @Test
    fun mergeRetainsPendingLocalMessageWhenCoreHasNotCommittedTheTurn() {
        val characterId = "owner:partition:galaxy.default"
        val conversationId = "00000000-0000-4000-8000-000000000403"
        val pending = ConversationMessage(
            id = pendingLilaMessageId("00000000-0000-4000-8000-000000000498"),
            characterId = characterId,
            role = MessageRole.USER,
            content = "still pending",
            createdAtEpochMillis = 900
        )

        val merged = mergeRemoteHistory(characterId, conversationId, listOf(pending), emptyList())

        assertEquals(listOf(pending), merged)
    }

    @Test
    fun mergeDoesNotConsumePendingMessageForAnOlderRemoteMessageWithTheSameText() {
        val characterId = "owner:partition:galaxy.default"
        val conversationId = "00000000-0000-4000-8000-000000000404"
        val pending = ConversationMessage(
            id = pendingLilaMessageId("00000000-0000-4000-8000-000000000497"),
            characterId = characterId,
            role = MessageRole.USER,
            content = "same text",
            createdAtEpochMillis = 20_000
        )
        val remote = listOf(
            LilaHistoryMessage(1, "user", "same text", 10_000),
            LilaHistoryMessage(2, "assistant", "older answer", 10_001)
        )

        val merged = mergeRemoteHistory(characterId, conversationId, listOf(pending), remote)

        assertEquals(3, merged.size)
        assertTrue(merged.any { it.id == pending.id })
        assertTrue(merged.any { it.content == "older answer" })
    }

    @Test
    fun mergePreservesLocalRowsAndOrdersRemoteMessagesWithEqualTimestamps() {
        val characterId = "owner:partition:galaxy.default"
        val conversationId = "00000000-0000-4000-8000-000000000401"
        val local = listOf(
            ConversationMessage(
                id = "local-user",
                characterId = characterId,
                role = MessageRole.USER,
                content = "hello",
                createdAtEpochMillis = 900
            ),
            ConversationMessage(
                id = "local-assistant",
                characterId = characterId,
                role = MessageRole.CHARACTER,
                content = "world",
                createdAtEpochMillis = 901
            ),
            ConversationMessage(
                id = "local-only",
                characterId = characterId,
                role = MessageRole.USER,
                content = "offline note",
                createdAtEpochMillis = 1500
            )
        )
        val remote = listOf(
            LilaHistoryMessage(1, "user", "hello", 1000),
            LilaHistoryMessage(2, "assistant", "world", 1000),
            LilaHistoryMessage(3, "user", "later user", 2000),
            LilaHistoryMessage(4, "assistant", "later answer", 2000)
        )

        val merged = mergeRemoteHistory(characterId, conversationId, local, remote)

        assertEquals(
            listOf(
                "local-user",
                "local-assistant",
                "local-only",
                "lila-$conversationId-00000000000000000003",
                "lila-$conversationId-00000000000000000004"
            ),
            merged.map { it.id }
        )
        assertEquals(
            listOf("hello", "world", "offline note", "later user", "later answer"),
            merged.map { it.content }
        )
        assertEquals(listOf(900L, 901L, 1500L, 2000L, 2000L), merged.map { it.createdAtEpochMillis })
    }

    @Test
    fun mergeRestoresFallbackNoticeFromRemoteHistoryWithoutDuplicatingLocalNotice() {
        val characterId = "owner:partition:galaxy.default"
        val conversationId = "00000000-0000-4000-8000-000000000405"
        val fallback = LilaHistoryMessage(
            sequence = 2,
            role = "assistant",
            content = "Ответ резервной модели",
            createdAtEpochMillis = 1001,
            providerId = "backup-provider",
            modelId = "backup-model",
            fallbackUsed = true,
            fallbackFromProviderId = "primary-provider",
            fallbackFromModelId = "primary-model",
            fallbackReason = "primary_connection_unavailable"
        )
        val noticeText = lilaFallbackNoticeText(
            fallbackUsed = fallback.fallbackUsed,
            providerId = fallback.providerId,
            modelId = fallback.modelId,
            fallbackFromProviderId = fallback.fallbackFromProviderId,
            fallbackFromModelId = fallback.fallbackFromModelId,
            fallbackReason = fallback.fallbackReason
        )!!
        val localNotice = ConversationMessage(
            id = "local-fallback-notice",
            characterId = characterId,
            role = MessageRole.SYSTEM,
            content = noticeText,
            createdAtEpochMillis = 1003
        )

        val merged = mergeRemoteHistory(
            characterId,
            conversationId,
            listOf(localNotice),
            listOf(
                LilaHistoryMessage(1, "user", "hello", 1000),
                fallback
            )
        )

        assertEquals(3, merged.size)
        assertEquals(MessageRole.CHARACTER, merged[1].role)
        assertEquals("Ответ резервной модели", merged[1].content)
        assertEquals(MessageRole.SYSTEM, merged[2].role)
        assertEquals("local-fallback-notice", merged[2].id)
        assertEquals(noticeText, merged[2].content)
    }
}
