package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class ComfyUiImageGenerationProviderTest {
    private val config = ComfyUiConnectionConfig("http://192.168.1.20:8188")
    private val request = ImageGenerationRequest(
        id = "galaxy-request-1", conversationId = "conversation-1", characterId = "lila",
        prompt = "Adult silver-haired fantasy heroine", workflowId = "lila-v1",
        width = 512, height = 768, createdAtEpochMillis = 1234L
    )
    private val template = ComfyUiWorkflowTemplate(
        id = "lila-v1", workflowJson = """{"1":{"inputs":{"text":"old"}}}""",
        positivePrompt = ComfyUiNodeInput("1", "text")
    )

    @Test fun capabilitiesAreConservative() = runBlocking {
        val c = provider(FakeTransport()).capabilities()
        assertEquals(setOf("image/png"), c.supportedMimeTypes)
        assertTrue(c.supportsQueuedCancellation)
        assertFalse(c.supportsRunningCancellation)
        assertFalse(c.supportsReferenceImages)
    }

    @Test fun submitStoresIntentBeforeNetworkAndUsesStablePromptId() = runBlocking {
        val promptId = stablePromptId(request)
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"prompt_id":"$promptId","number":2}"""))
        }
        val store = InMemoryComfyUiJobStore()
        assertEquals(promptId, provider(transport, store).submit(request))
        assertEquals(ComfyUiJobRecord(promptId, request.id, 1234L, submissionStarted = true), store.get(promptId))
        val payload = JSONObject(String(transport.bodies.single()!!, Charsets.UTF_8))
        assertEquals(promptId, payload.getString("prompt_id"))
        assertEquals(request.prompt, payload.getJSONObject("prompt").getJSONObject("1")
            .getJSONObject("inputs").getString("text"))
    }

    @Test fun lostSubmissionAcknowledgementKeepsIntentAndRetryDoesNotResubmit() = runBlocking {
        val transport = FakeTransport().apply {
            failure = IOException("simulated connection loss after request write")
        }
        val store = InMemoryComfyUiJobStore()
        val activeProvider = provider(transport, store)

        val failure = assertThrows(ComfyUiSubmissionUncertainException::class.java) {
            runBlocking { activeProvider.submit(request) }
        }

        val expectedPromptId = stablePromptId(request)
        assertEquals(expectedPromptId, failure.promptId)
        assertEquals(ComfyUiJobRecord(expectedPromptId, request.id, request.createdAtEpochMillis,
            submissionStarted = true), store.get(expectedPromptId))
        assertEquals(listOf("POST"), transport.methods)

        // Retrying the same logical request returns its journaled ID without a second POST.
        assertEquals(expectedPromptId, activeProvider.submit(request))
        assertEquals(listOf("POST"), transport.methods)
    }

    @Test fun conflictResponseKeepsIntentForQueueHistoryReconciliation() = runBlocking {
        val promptId = stablePromptId(request)
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(409, "{}"))
        }
        val store = InMemoryComfyUiJobStore()

        val failure = assertThrows(ComfyUiSubmissionUncertainException::class.java) {
            runBlocking { provider(transport, store).submit(request) }
        }

        assertEquals(promptId, failure.promptId)
        assertEquals(
            ComfyUiJobRecord(promptId, request.id, request.createdAtEpochMillis, submissionStarted = true),
            store.get(promptId)
        )
        assertEquals(listOf("POST"), transport.methods)
    }

    @Test fun cancellationDuringSubmissionKeepsIntentAndRetryDoesNotResubmit() = runBlocking {
        val transport = FakeTransport().apply {
            failure = CancellationException("simulated caller cancellation")
        }
        val store = InMemoryComfyUiJobStore()
        val activeProvider = provider(transport, store)

        val cancellation = assertThrows(ComfyUiSubmissionCancelledException::class.java) {
            runBlocking { activeProvider.submit(request) }
        }

        val expectedPromptId = stablePromptId(request)
        assertEquals(expectedPromptId, cancellation.promptId)
        assertEquals(ComfyUiJobRecord(expectedPromptId, request.id, request.createdAtEpochMillis,
            submissionStarted = true), store.get(expectedPromptId))
        assertEquals(expectedPromptId, activeProvider.submit(request))
        assertEquals(listOf("POST"), transport.methods)
    }


    @Test fun cancelledSubmissionReconcilesExistingPromptFromQueueWithoutResubmitting() = runBlocking {
        val transport = FakeTransport().apply {
            failure = CancellationException("simulated caller cancellation")
        }
        val store = InMemoryComfyUiJobStore()
        val activeProvider = provider(transport, store)

        val cancellation = assertThrows(ComfyUiSubmissionCancelledException::class.java) {
            runBlocking { activeProvider.submit(request) }
        }
        val expectedPromptId = stablePromptId(request)
        assertEquals(expectedPromptId, cancellation.promptId)

        transport.failure = null
        transport.responses.add(
            ComfyUiApiResponse(
                200,
                """{"queue_running":[],"queue_pending":[[1,"$expectedPromptId",{},[],[]]]}"""
            )
        )

        val recovered = activeProvider.status(expectedPromptId)

        assertEquals(ImageGenerationState.QUEUED, recovered.state)
        assertEquals(expectedPromptId, activeProvider.submit(request))
        assertEquals(listOf("POST", "GET"), transport.methods)
        assertEquals(
            listOf(
                "http://192.168.1.20:8188/prompt",
                "http://192.168.1.20:8188/queue"
            ),
            transport.urls
        )
    }

    @Test fun cancelledSubmissionReconcilesQueueMissThroughHistoryWithoutResubmitting() = runBlocking {
        val transport = FakeTransport().apply {
            failure = CancellationException("simulated caller cancellation")
        }
        val store = InMemoryComfyUiJobStore()
        val activeProvider = provider(transport, store)

        val cancellation = assertThrows(ComfyUiSubmissionCancelledException::class.java) {
            runBlocking { activeProvider.submit(request) }
        }
        val expectedPromptId = stablePromptId(request)
        assertEquals(expectedPromptId, cancellation.promptId)

        transport.failure = null
        transport.responses.add(
            ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[]}""")
        )
        transport.responses.add(
            ComfyUiApiResponse(
                200,
                """{"$expectedPromptId":{"status":{"status_str":"success","completed":true},"outputs":{}}}"""
            )
        )

        val recovered = activeProvider.status(expectedPromptId)

        assertEquals(ImageGenerationState.COMPLETED, recovered.state)
        assertEquals(expectedPromptId, activeProvider.submit(request))
        assertEquals(listOf("POST", "GET", "GET"), transport.methods)
        assertEquals(
            listOf(
                "http://192.168.1.20:8188/prompt",
                "http://192.168.1.20:8188/queue",
                "http://192.168.1.20:8188/history/$expectedPromptId"
            ),
            transport.urls
        )
    }

    @Test fun localIntentWriteFailurePreventsRemoteSubmission() {
        val transport = FakeTransport()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider(transport, FailingJobStore()).submit(request) }
        }
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun stablePromptIdCollisionWithAnotherRequestIsRejectedBeforeNetworkCall() {
        val promptId = stablePromptId(request)
        val transport = FakeTransport()
        val existing = ComfyUiJobRecord(promptId, "another-request", 999L)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider(transport, ExistingJobStore(existing)).submit(request) }
        }

        assertTrue(transport.urls.isEmpty())
    }

    @Test fun continuesWhenIntentWriteCommittedBeforeStoreAcknowledgementFailed() = runBlocking {
        val promptId = stablePromptId(request)
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"prompt_id":"$promptId","number":2}"""))
        }
        val store = CommitThenFailJobStore()

        assertEquals(promptId, provider(transport, store).submit(request))
        assertEquals(ComfyUiJobRecord(promptId, request.id, request.createdAtEpochMillis,
            submissionStarted = true), store.get(promptId))
        assertEquals(listOf("POST"), transport.methods)
    }

    @Test fun intentReadbackFailurePreventsRemoteSubmission() {
        val transport = FakeTransport()

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { provider(transport, ReadbackFailingJobStore()).submit(request) }
        }

        assertEquals("simulated SQLCipher write failure", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertEquals("simulated SQLCipher readback failure", failure.suppressed.single().message)
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun queuedCancellationDeletesOnlyThatPrompt() = runBlocking {
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[[1,"prompt-123",{},[],[]]]}"""))
            responses.add(ComfyUiApiResponse(200, """{"deleted":["prompt-123"]}"""))
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[]}"""))
        }
        val store = InMemoryComfyUiJobStore().apply {
            put(ComfyUiJobRecord("prompt-123", request.id, request.createdAtEpochMillis))
        }
        provider(transport, store).cancel("prompt-123")
        assertTrue(store.get("prompt-123")!!.cancelled)
        assertEquals(listOf("GET", "POST", "GET", "GET"), transport.methods)
        assertTrue(String(transport.bodies[1]!!, Charsets.UTF_8).contains("prompt-123"))
        assertFalse(transport.urls.any { it.endsWith("/interrupt") })
    }

    @Test fun cancellationAfterRemoteDeleteStillPersistsLocalCancelledState() = runBlocking {
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[[1,"prompt-123",{},[],[]]]}"""))
            responses.add(ComfyUiApiResponse(200, """{"deleted":["prompt-123"]}"""))
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[]}"""))
        }
        val store = PausingMarkCancelledJobStore(
            ComfyUiJobRecord("prompt-123", request.id, request.createdAtEpochMillis)
        )
        val cancellation = launch { provider(transport, store).cancel("prompt-123") }

        store.markStarted.await()
        cancellation.cancel()
        store.allowMark.complete(Unit)
        cancellation.join()

        assertTrue(store.get("prompt-123")!!.cancelled)
        assertEquals(listOf("GET", "POST", "GET", "GET"), transport.methods)
    }

    @Test fun completedJobIsNotMarkedCancelledWhenItFinishesDuringQueueDeletion() {
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[[1,"prompt-123",{},[],[]]]}"""))
            responses.add(ComfyUiApiResponse(200, """{}"""))
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[]}"""))
            responses.add(ComfyUiApiResponse(200, """{"prompt-123":{"status":{"status_str":"success","completed":true},"outputs":{}}}"""))
        }
        val store = InMemoryComfyUiJobStore().apply {
            runBlocking { put(ComfyUiJobRecord("prompt-123", request.id, request.createdAtEpochMillis)) }
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider(transport, store).cancel("prompt-123") }
        }
        assertFalse(runBlocking { store.get("prompt-123")!!.cancelled })
        assertEquals(listOf("GET", "POST", "GET", "GET"), transport.methods)
    }

    @Test fun runningCancellationIsRejectedWithoutDelete() {
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[[0,"prompt-123",{},[],[]]],"queue_pending":[]}"""))
        }
        val store = InMemoryComfyUiJobStore().apply {
            runBlocking { put(ComfyUiJobRecord("prompt-123", request.id, request.createdAtEpochMillis)) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider(transport, store).cancel("prompt-123") }
        }
        assertEquals(listOf("GET"), transport.methods)
    }

    @Test fun referenceImagesAreRejectedBeforeNetworkCall() {
        val transport = FakeTransport()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { provider(transport).submit(request.copy(referenceAssetIds = listOf("ref-1"))) }
        }
        assertTrue(transport.urls.isEmpty())
    }

    @Test fun completedPngReturnsValidatedMetadata() = runBlocking {
        val png = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(png)
        "IHDR".toByteArray().copyInto(png, 12)
        png[18] = 2; png[19] = 0
        png[22] = 3; png[23] = 0
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{
                "prompt-123":{"status":{"status_str":"success","completed":true},
                "outputs":{"9":{"images":[{"filename":"lila.png","subfolder":"renders","type":"output"}]}}}
            }"""))
            binary = ComfyUiBinaryResponse(200, "image/png", png)
        }
        val store = InMemoryComfyUiJobStore().apply {
            put(ComfyUiJobRecord("prompt-123", request.id, request.createdAtEpochMillis))
        }
        val asset = provider(transport, store).result("prompt-123")
        assertEquals(request.id, asset.requestId)
        assertEquals(512, asset.width)
        assertEquals(768, asset.height)
        assertEquals(png.size.toLong(), asset.byteSize)
        assertEquals(64, asset.sha256.length)
    }

    @Test fun completedImageReturnsMetadataAndBytesFromOneDownload() = runBlocking {
        val png = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(png)
        "IHDR".toByteArray().copyInto(png, 12)
        png[18] = 2; png[19] = 0
        png[22] = 3; png[23] = 0
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """
                {"prompt-123":{"status":{"status_str":"success","completed":true},
                "outputs":{"9":{"images":[{"filename":"lila.png","subfolder":"renders","type":"output"}]}}}}
            """.trimIndent()))
            binary = ComfyUiBinaryResponse(200, "image/png", png)
        }
        val store = InMemoryComfyUiJobStore().apply {
            put(ComfyUiJobRecord("prompt-123", request.id, request.createdAtEpochMillis))
        }

        val result = provider(transport, store).completedImage("prompt-123")

        assertEquals(request.id, result.asset.requestId)
        assertEquals(png.size.toLong(), result.asset.byteSize)
        assertEquals("image/png", result.mimeType)
        assertTrue(result.bytes.contentEquals(png))
        assertEquals(1, transport.urls.count { it.contains("/view?") })
    }

    private class PausingMarkCancelledJobStore(
        initial: ComfyUiJobRecord
    ) : ComfyUiJobStore {
        private var record = initial
        val markStarted = CompletableDeferred<Unit>()
        val allowMark = CompletableDeferred<Unit>()

        override suspend fun put(record: ComfyUiJobRecord) {
            this.record = record
        }

        override suspend fun get(promptId: String): ComfyUiJobRecord? =
            record.takeIf { it.promptId == promptId }

        override suspend fun markSubmissionStarted(promptId: String): Boolean {
            if (record.promptId != promptId || record.submissionStarted) return false
            record = record.copy(submissionStarted = true)
            return true
        }

        override suspend fun markCancelled(promptId: String) {
            markStarted.complete(Unit)
            allowMark.await()
            record = record.copy(cancelled = true)
        }
    }

    private class ExistingJobStore(
        private val existing: ComfyUiJobRecord
    ) : ComfyUiJobStore {
        override suspend fun put(record: ComfyUiJobRecord) {
            throw IllegalStateException("duplicate prompt ID")
        }

        override suspend fun get(promptId: String): ComfyUiJobRecord? =
            existing.takeIf { it.promptId == promptId }

        override suspend fun markSubmissionStarted(promptId: String): Boolean = false

        override suspend fun markCancelled(promptId: String) = Unit
    }

    private class CommitThenFailJobStore : ComfyUiJobStore {
        private val records = mutableMapOf<String, ComfyUiJobRecord>()

        override suspend fun put(record: ComfyUiJobRecord) {
            records[record.promptId] = record
            throw IllegalStateException("simulated acknowledgement failure after commit")
        }

        override suspend fun get(promptId: String): ComfyUiJobRecord? = records[promptId]

        override suspend fun markSubmissionStarted(promptId: String): Boolean {
            val record = records[promptId] ?: return false
            records[promptId] = record.copy(submissionStarted = true)
            return true
        }

        override suspend fun markCancelled(promptId: String) = Unit
    }

    private class ReadbackFailingJobStore : ComfyUiJobStore {
        private var reads = 0

        override suspend fun put(record: ComfyUiJobRecord) {
            throw IllegalStateException("simulated SQLCipher write failure")
        }

        override suspend fun get(promptId: String): ComfyUiJobRecord? {
            if (reads++ == 0) return null
            throw IllegalStateException("simulated SQLCipher readback failure")
        }

        override suspend fun markSubmissionStarted(promptId: String): Boolean = false

        override suspend fun markCancelled(promptId: String) = Unit
    }

    private class FailingJobStore : ComfyUiJobStore {
        override suspend fun put(record: ComfyUiJobRecord) {
            throw IllegalStateException("simulated SQLCipher write failure")
        }

        override suspend fun get(promptId: String): ComfyUiJobRecord? = null

        override suspend fun markSubmissionStarted(promptId: String): Boolean = false

        override suspend fun markCancelled(promptId: String) = Unit
    }

    private fun stablePromptId(request: ImageGenerationRequest): String =
        UUID.nameUUIDFromBytes(request.id.toByteArray(Charsets.UTF_8)).toString()

    private fun provider(
        transport: FakeTransport,
        store: ComfyUiJobStore = InMemoryComfyUiJobStore()
    ) = ComfyUiImageGenerationProvider(config, template, "galaxy-test", ComfyUiApiClient(transport), store)

    private class FakeTransport : ComfyUiApiTransport {
        val responses = ArrayDeque<ComfyUiApiResponse>()
        val urls = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray?>()
        var binary = ComfyUiBinaryResponse(200, "image/png", byteArrayOf())
        var failure: Exception? = null
        override suspend fun execute(
            method: String, url: String, body: ByteArray?, bearerToken: String?,
            connectTimeoutMillis: Int, readTimeoutMillis: Int, maxResponseBytes: Int
        ): ComfyUiApiResponse {
            urls += url; methods += method; bodies += body
            failure?.let { throw it }
            return if (responses.isEmpty()) ComfyUiApiResponse(200, "{}") else responses.removeFirst()
        }
        override suspend fun getBinary(
            url: String, bearerToken: String?, connectTimeoutMillis: Int,
            readTimeoutMillis: Int, maxResponseBytes: Int
        ): ComfyUiBinaryResponse {
            urls += url
            return binary
        }
    }
}
