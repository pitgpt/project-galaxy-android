package com.pitgpt.galaxy.ui

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.core.repository.ImageGenerationRequestStore
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobStore
import com.pitgpt.galaxy.data.remote.GalaxyApiException
import com.pitgpt.galaxy.data.remote.LilaBinaryResponse
import com.pitgpt.galaxy.data.remote.LilaCoreImageGenerationProvider
import com.pitgpt.galaxy.data.remote.LilaImageApiTransport
import com.pitgpt.galaxy.data.remote.LilaImageHistoryJob
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreImageHistorySyncTest {
    @Test
    fun completedCoreAssetIsImportedOnceAndPlaintextResponseIsCleared() = runBlocking {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/nY8AAAAASUVORK5CYII="
        )
        val responseBytes = png.copyOf()
        val digest = sha256(png)
        val jobId = "00000000-0000-4000-8000-000000000301"
        val conversationId = "00000000-0000-4000-8000-000000000302"
        val historyJob = LilaImageHistoryJob(
            jobId = jobId,
            conversationId = conversationId,
            prompt = "silver-haired heroine",
            negativePrompt = "blurry",
            width = 1024,
            height = 1024,
            seed = 42L,
            state = "completed",
            createdAtEpochMillis = 1_700_000_000_000L,
            assetByteSize = png.size.toLong()
        )
        val api = FakeImageApi(
            jobId = jobId,
            conversationId = conversationId,
            png = responseBytes,
            digest = digest
        )
        val jobStore = FakeJobStore()
        val requestStore = FakeRequestStore()
        val images = FakeGeneratedImageRepository()
        val provider = LilaCoreImageGenerationProvider(api, jobStore)

        val first = syncCoreImageHistory(
            provider = provider,
            conversationId = conversationId,
            characterId = "galaxy.default",
            requestStore = requestStore,
            jobStore = jobStore,
            images = images,
            history = listOf(historyJob)
        )

        assertEquals(1, first.importedImages.size)
        assertEquals(0, first.skippedAssets)
        assertEquals(0, first.failedJobs)
        assertEquals(1, api.binaryRequests)
        assertArrayEquals(ByteArray(responseBytes.size), responseBytes)
        assertArrayEquals(png, images.savedBytes)
        assertEquals(conversationId, first.importedImages.single().conversationId)
        assertEquals("galaxy.default", first.importedImages.single().characterId)
        assertEquals(jobId, requestStore.get(jobId)?.id)
        assertEquals(jobId, jobStore.get(jobId)?.requestId)

        val second = syncCoreImageHistory(
            provider = provider,
            conversationId = conversationId,
            characterId = "galaxy.default",
            requestStore = requestStore,
            jobStore = jobStore,
            images = images,
            history = listOf(historyJob)
        )

        assertTrue(second.importedImages.isEmpty())
        assertEquals(0, second.failedJobs)
        assertEquals(1, api.binaryRequests)
        assertEquals(1, images.savedCount)
    }

    @Test
    fun coreHistoryForAnotherConversationIsNotImported() = runBlocking {
        val requestedConversation = "00000000-0000-4000-8000-000000000312"
        val otherConversation = "00000000-0000-4000-8000-000000000313"
        val job = LilaImageHistoryJob(
            jobId = "00000000-0000-4000-8000-000000000314",
            conversationId = otherConversation,
            prompt = "must not cross conversations",
            negativePrompt = null,
            width = 512,
            height = 512,
            seed = 7L,
            state = "completed",
            createdAtEpochMillis = 1_700_000_000_000L,
            assetByteSize = 128L
        )
        val jobStore = FakeJobStore()
        val requestStore = FakeRequestStore()
        val images = FakeGeneratedImageRepository()
        val api = FakeImageApi(
            jobId = job.jobId,
            conversationId = otherConversation,
            png = ByteArray(0),
            digest = "0".repeat(64)
        )

        val result = syncCoreImageHistory(
            provider = LilaCoreImageGenerationProvider(api, jobStore),
            conversationId = requestedConversation,
            characterId = "galaxy.default",
            requestStore = requestStore,
            jobStore = jobStore,
            images = images,
            history = listOf(job)
        )

        assertEquals(1, result.failedJobs)
        assertTrue(result.importedImages.isEmpty())
        assertNull(requestStore.get(job.jobId))
        assertNull(jobStore.get(job.jobId))
        assertEquals(0, api.binaryRequests)
        assertEquals(0, images.savedCount)
    }

    private class FakeImageApi(
        jobId: String,
        conversationId: String,
        private val png: ByteArray,
        digest: String
    ) : LilaImageApiTransport {
        private val job = JSONObject()
            .put("job_id", jobId)
            .put("conversation_id", conversationId)
            .put("prompt", "silver-haired heroine")
            .put("negative_prompt", "blurry")
            .put("width", 1)
            .put("height", 1)
            .put("seed", 42L)
            .put("state", "completed")
            .put("created_at_epoch_millis", 1_700_000_000_000L)
            .put(
                "asset",
                JSONObject()
                    .put("mime_type", "image/png")
                    .put("width", 1)
                    .put("height", 1)
                    .put("byte_size", png.size)
                    .put("sha256", digest)
            )

        var binaryRequests = 0
            private set

        override suspend fun requestJson(
            method: String,
            path: String,
            body: JSONObject?,
            idempotencyKey: String?,
            maxResponseBytes: Int,
            maxRequestBytes: Int
        ): JSONObject {
            assertEquals("GET", method)
            assertTrue(path.endsWith("/${job.optString("job_id")}"))
            return JSONObject(job.toString())
        }

        override suspend fun requestBinary(
            path: String,
            maxResponseBytes: Int,
            expectedContentType: String
        ): LilaBinaryResponse {
            assertTrue(path.endsWith("/asset"))
            assertEquals(25 * 1024 * 1024, maxResponseBytes)
            assertEquals("image/png", expectedContentType)
            binaryRequests++
            return LilaBinaryResponse("image/png", png)
        }
    }

    private class FakeJobStore : ComfyUiJobStore {
        private val records = linkedMapOf<String, ComfyUiJobRecord>()

        override suspend fun put(record: ComfyUiJobRecord) {
            check(records.putIfAbsent(record.promptId, record) == null)
        }

        override suspend fun get(promptId: String): ComfyUiJobRecord? = records[promptId]

        override suspend fun markCancelled(promptId: String) {
            val record = checkNotNull(records[promptId])
            records[promptId] = record.copy(cancelled = true)
        }

        override suspend fun all(): List<ComfyUiJobRecord> = records.values.toList()

        override suspend fun markSubmissionStarted(promptId: String): Boolean {
            val record = records[promptId] ?: return false
            if (record.submissionStarted || record.cancelled) return false
            records[promptId] = record.copy(submissionStarted = true)
            return true
        }
    }

    private class FakeRequestStore : ImageGenerationRequestStore {
        private val requests = linkedMapOf<String, ImageGenerationRequest>()

        override suspend fun put(request: ImageGenerationRequest) {
            requests[request.id] = request
        }

        override suspend fun get(requestId: String): ImageGenerationRequest? = requests[requestId]

        override suspend fun all(): List<ImageGenerationRequest> = requests.values.toList()

        override suspend fun latestForCharacter(characterId: String): ImageGenerationRequest? =
            requests.values.filter { it.characterId == characterId }.maxByOrNull { it.createdAtEpochMillis }

        override suspend fun delete(requestId: String) {
            requests.remove(requestId)
        }
    }

    private class FakeGeneratedImageRepository : GeneratedImageRepository {
        private val byRequest = linkedMapOf<String, StoredGeneratedImage>()
        private val bytesByAsset = linkedMapOf<String, ByteArray>()
        var savedBytes: ByteArray? = null
            private set
        val savedCount: Int get() = byRequest.size

        override suspend fun save(
            request: ImageGenerationRequest,
            asset: GeneratedImageAsset,
            imageBytes: ByteArray
        ): StoredGeneratedImage {
            val stored = StoredGeneratedImage(
                assetId = UUID.randomUUID().toString(),
                requestId = request.id,
                conversationId = request.conversationId,
                characterId = request.characterId,
                mimeType = asset.mimeType,
                width = asset.width,
                height = asset.height,
                byteSize = asset.byteSize,
                sha256 = asset.sha256,
                createdAtEpochMillis = asset.createdAtEpochMillis
            )
            val copy = imageBytes.copyOf()
            byRequest[request.id] = stored
            bytesByAsset[stored.assetId] = copy
            savedBytes = copy.copyOf()
            return stored
        }

        override suspend fun getForRequest(requestId: String): StoredGeneratedImage? = byRequest[requestId]

        override suspend fun get(assetId: String): StoredGeneratedImage? =
            byRequest.values.firstOrNull { it.assetId == assetId }

        override suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage> =
            byRequest.values.filter { it.conversationId == conversationId }

        override suspend fun read(assetId: String): ByteArray =
            bytesByAsset[assetId]?.copyOf() ?: error("Unknown image asset")

        override suspend fun delete(assetId: String) {
            val entry = byRequest.entries.firstOrNull { it.value.assetId == assetId } ?: return
            byRequest.remove(entry.key)
            bytesByAsset.remove(assetId)?.fill(0)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
