package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComfyUiApiClientTest {
    private val config = ComfyUiConnectionConfig("http://192.168.1.20:8188", bearerToken = "test-token")
    private val request = ImageGenerationRequest(
        id = "request-1",
        conversationId = "conversation-1",
        characterId = "lila",
        prompt = "silver-haired adult heroine",
        negativePrompt = "blurry",
        workflowId = "lila-v1",
        width = 768,
        height = 1024,
        seed = 42L,
        createdAtEpochMillis = 1L
    )
    private val template = ComfyUiWorkflowTemplate(
        id = "lila-v1",
        workflowJson = """{
            "1":{"class_type":"CLIPTextEncode","inputs":{"text":"old positive","clip":["4",1]}},
            "2":{"class_type":"CLIPTextEncode","inputs":{"text":"old negative","clip":["4",1]}},
            "3":{"class_type":"EmptyLatentImage","inputs":{"width":512,"height":512,"batch_size":1}},
            "4":{"class_type":"KSampler","inputs":{"seed":7,"steps":20}}
        }""",
        positivePrompt = ComfyUiNodeInput("1", "text"),
        negativePrompt = ComfyUiNodeInput("2", "text"),
        width = ComfyUiNodeInput("3", "width"),
        height = ComfyUiNodeInput("3", "height"),
        seed = ComfyUiNodeInput("4", "seed")
    )

    @Test
    fun mapsOnlyExplicitWorkflowInputs() {
        val graph = template.buildPrompt(request)

        assertEquals("silver-haired adult heroine", graph.getJSONObject("1").getJSONObject("inputs").getString("text"))
        assertEquals("blurry", graph.getJSONObject("2").getJSONObject("inputs").getString("text"))
        assertEquals(768, graph.getJSONObject("3").getJSONObject("inputs").getInt("width"))
        assertEquals(1024, graph.getJSONObject("3").getJSONObject("inputs").getInt("height"))
        assertEquals(42L, graph.getJSONObject("4").getJSONObject("inputs").getLong("seed"))
        assertEquals(20, graph.getJSONObject("4").getJSONObject("inputs").getInt("steps"))
    }

    @Test
    fun refusesWorkflowIdMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            template.buildPrompt(request.copy(workflowId = "other-workflow"))
        }
    }

    @Test
    fun validatesExplicitMappingsBeforeTemplateIsSaved() {
        val broken = template.copy(
            width = ComfyUiNodeInput("3", "missing_input")
        )

        assertThrows(IllegalArgumentException::class.java) {
            broken.validateMappings()
        }
    }

    @Test
    fun refusesMissingMappedWorkflowInput() {
        val broken = template.copy(
            positivePrompt = ComfyUiNodeInput("1", "missing_input")
        )
        assertThrows(IllegalArgumentException::class.java) {
            broken.buildPrompt(request)
        }
    }

    @Test
    fun submitsPromptAndReturnsProviderPromptId() = runBlocking {
        val transport = FakeTransport().apply {
            jsonResponse = ComfyUiApiResponse(200, """{"prompt_id":"job_123","number":7,"node_errors":{}}""")
        }
        val result = ComfyUiApiClient(transport).submit(config, template, request, "galaxy-client")

        assertEquals("job_123", result.promptId)
        assertEquals(7L, result.queueNumber)
        assertEquals("POST", transport.method)
        assertEquals("http://192.168.1.20:8188/prompt", transport.url)
        val payload = JSONObject(String(transport.requestBody!!, Charsets.UTF_8))
        assertEquals("galaxy-client", payload.getString("client_id"))
        assertEquals("silver-haired adult heroine",
            payload.getJSONObject("prompt").getJSONObject("1").getJSONObject("inputs").getString("text"))
        assertEquals("test-token", transport.token)
    }

    @Test
    fun rejectsInvalidPromptIdFromProvider() {
        runBlocking {
            val transport = FakeTransport().apply {
                jsonResponse = ComfyUiApiResponse(200, """{"prompt_id":"../admin"}""")
            }
            assertThrows(ComfyUiApiException::class.java) {
                runBlocking { ComfyUiApiClient(transport).submit(config, template, request, "client-1") }
            }
        }
    }

    @Test
    fun parsesRunningAndPendingQueueEntries() = runBlocking {
        val transport = FakeTransport().apply {
            jsonResponse = ComfyUiApiResponse(
                200,
                """{"queue_running":[[0,"running-1",{},[],[]]],"queue_pending":[[1,"pending-2",{},[],[]]]}"""
            )
        }
        val queue = ComfyUiApiClient(transport).queue(config)

        assertEquals(setOf("running-1"), queue.runningPromptIds)
        assertEquals(setOf("pending-2"), queue.pendingPromptIds)
    }

    @Test
    fun parsesHistoryStatusAndOutputImageReferences() = runBlocking {
        val transport = FakeTransport().apply {
            jsonResponse = ComfyUiApiResponse(
                200,
                """{"job_123":{"status":{"status_str":"success","completed":true},
                    "outputs":{"9":{"images":[{"filename":"lila.png","subfolder":"2026/11","type":"output"}]}}}}"""
            )
        }
        val history = ComfyUiApiClient(transport).history(config, "job_123")

        assertEquals(ImageGenerationState.COMPLETED, history.status.state)
        assertEquals(1, history.images.size)
        assertEquals("lila.png", history.images.single().filename)
        assertEquals("2026/11", history.images.single().subfolder)
    }

    @Test
    fun rejectsUnsafePromptIdBeforeNetworkCall() {
        val transport = FakeTransport()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ComfyUiApiClient(transport).history(config, "../admin") }
        }
        assertEquals(null, transport.url)
    }

    @Test
    fun rejectsUnsafeImageReferencePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiImageReference("../secret.png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiImageReference("image.png", "../outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiImageReference("image.png", type = "unknown")
        }
    }

    @Test
    fun downloadsBoundedImageInMemoryAndComputesSha256() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
        val transport = FakeTransport().apply {
            binaryResponse = ComfyUiBinaryResponse(200, "image/png; charset=binary", png)
        }
        val reference = ComfyUiImageReference("lila image.png", "renders/one")
        val result = ComfyUiApiClient(transport).download(config, reference)

        assertEquals("image/png", result.mimeType)
        assertTrue(result.bytes.contentEquals(png))
        assertEquals(64, result.sha256.length)
        assertTrue(transport.url!!.contains("filename=lila+image.png"))
        assertTrue(transport.url!!.contains("subfolder=renders%2Fone"))
        assertEquals(ComfyUiApiClient.MAX_IMAGE_BYTES, transport.maxResponseBytes)
    }

    @Test
    fun rejectsImageWithMismatchedContentTypeAndSignature() {
        runBlocking {
            val transport = FakeTransport().apply {
                binaryResponse = ComfyUiBinaryResponse(200, "image/png", "<html>".toByteArray())
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    ComfyUiApiClient(transport).download(config, ComfyUiImageReference("fake.png"))
                }
            }
        }
    }

    @Test
    fun deleteQueuedUsesOnlyTheSpecificPromptId() = runBlocking {
        val transport = FakeTransport().apply {
            jsonResponse = ComfyUiApiResponse(200, """{"deleted":["job_123"]}""")
        }
        ComfyUiApiClient(transport).deleteQueued(config, "job_123")

        assertEquals("POST", transport.method)
        assertEquals("http://192.168.1.20:8188/queue", transport.url)
        val payload = JSONObject(String(transport.requestBody!!, Charsets.UTF_8))
        assertEquals("job_123", payload.getJSONArray("delete").getString(0))
    }

    @Test
    fun apiResponseStringDoesNotExposeResponseBody() {
        val privateResponse = "private-prompt-and-token-do-not-log"
        val response = ComfyUiApiResponse(500, """{"error":"$privateResponse"}""")

        val rendered = response.toString()

        assertFalse(rendered.contains(privateResponse))
        assertTrue(rendered.contains("statusCode=500"))
        assertTrue(rendered.contains("bodyChars="))
    }

    @Test
    fun doesNotIncludeProviderErrorBodyInException() = runBlocking {
        val transport = FakeTransport().apply {
            jsonResponse = ComfyUiApiResponse(401, """{"error":"secret-token-must-not-leak"}""")
        }
        val error = assertThrows(ComfyUiApiException::class.java) {
            runBlocking { ComfyUiApiClient(transport).queue(config) }
        }
        assertFalse(error.message.orEmpty().contains("secret-token-must-not-leak"))
        assertEquals(401, error.httpStatusCode)
    }

    private class FakeTransport : ComfyUiApiTransport {
        var method: String? = null
        var url: String? = null
        var token: String? = null
        var requestBody: ByteArray? = null
        var maxResponseBytes: Int? = null
        var jsonResponse = ComfyUiApiResponse(200, "{}")
        var binaryResponse = ComfyUiBinaryResponse(200, "image/png", byteArrayOf())

        override suspend fun execute(
            method: String,
            url: String,
            body: ByteArray?,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiApiResponse {
            this.method = method
            this.url = url
            this.requestBody = body
            this.token = bearerToken
            this.maxResponseBytes = maxResponseBytes
            return jsonResponse
        }

        override suspend fun getBinary(
            url: String,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiBinaryResponse {
            this.url = url
            this.token = bearerToken
            this.maxResponseBytes = maxResponseBytes
            return binaryResponse
        }
    }
}
