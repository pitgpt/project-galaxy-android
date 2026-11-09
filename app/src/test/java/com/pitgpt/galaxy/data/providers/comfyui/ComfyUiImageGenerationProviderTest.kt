package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
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

    @Test fun submitStoresOnlyRequestCorrelation() = runBlocking {
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"prompt_id":"prompt-123","number":2}"""))
        }
        val store = InMemoryComfyUiJobStore()
        assertEquals("prompt-123", provider(transport, store).submit(request))
        assertEquals(ComfyUiJobRecord("prompt-123", request.id, 1234L), store.get("prompt-123"))
        val payload = JSONObject(String(transport.bodies.single()!!, Charsets.UTF_8))
        assertEquals(request.prompt, payload.getJSONObject("prompt").getJSONObject("1")
            .getJSONObject("inputs").getString("text"))
    }

    @Test fun pendingSubmissionIsRemovedWhenJobCorrelationCannotBeStored() {
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"prompt_id":"prompt-123","number":2}"""))
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[[1,"prompt-123",{},[],[]]]}"""))
            responses.add(ComfyUiApiResponse(200, """{"deleted":["prompt-123"]}"""))
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[]}"""))
            responses.add(ComfyUiApiResponse(200, """{}"""))
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider(transport, FailingJobStore()).submit(request) }
        }

        assertEquals(listOf("POST", "GET", "POST", "GET", "GET"), transport.methods)
        assertTrue(String(transport.bodies[2]!!, Charsets.UTF_8).contains("prompt-123"))
        assertFalse(transport.urls.any { it.endsWith("/interrupt") })
    }

    @Test fun runningSubmissionIsNeverInterruptedWhenJobCorrelationCannotBeStored() {
        val transport = FakeTransport().apply {
            responses.add(ComfyUiApiResponse(200, """{"prompt_id":"prompt-123","number":2}"""))
            responses.add(ComfyUiApiResponse(200, """{"queue_running":[[0,"prompt-123",{},[],[]]],"queue_pending":[]}"""))
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider(transport, FailingJobStore()).submit(request) }
        }

        assertEquals(listOf("POST", "GET"), transport.methods)
        assertFalse(transport.urls.any { it.endsWith("/interrupt") })
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

    private class FailingJobStore : ComfyUiJobStore {
        override suspend fun put(record: ComfyUiJobRecord) {
            throw IllegalStateException("simulated SQLCipher write failure")
        }

        override suspend fun get(promptId: String): ComfyUiJobRecord? = null

        override suspend fun markCancelled(promptId: String) = Unit
    }

    private fun provider(
        transport: FakeTransport,
        store: InMemoryComfyUiJobStore = InMemoryComfyUiJobStore()
    ) = ComfyUiImageGenerationProvider(config, template, "galaxy-test", ComfyUiApiClient(transport), store)

    private class FakeTransport : ComfyUiApiTransport {
        val responses = ArrayDeque<ComfyUiApiResponse>()
        val urls = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val bodies = mutableListOf<ByteArray?>()
        var binary = ComfyUiBinaryResponse(200, "image/png", byteArrayOf())
        override suspend fun execute(
            method: String, url: String, body: ByteArray?, bearerToken: String?,
            connectTimeoutMillis: Int, readTimeoutMillis: Int, maxResponseBytes: Int
        ): ComfyUiApiResponse {
            urls += url; methods += method; bodies += body
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
