package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.data.providers.ComfyUiSavedSettings
import com.pitgpt.galaxy.data.providers.ProviderSettings
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUiRuntimeFactoryTest {
    private val config = ComfyUiConnectionConfig("http://192.168.1.20:8188")
    private val request = ImageGenerationRequest(
        id = "request-1",
        conversationId = "conversation-1",
        characterId = "character-1",
        prompt = "A test image",
        workflowId = "workflow-v1",
        width = 64,
        height = 64,
        createdAtEpochMillis = 100L
    )
    private val template = ComfyUiWorkflowTemplate(
        id = "workflow-v1",
        workflowJson = """{"1":{"inputs":{"text":"old"}}}""",
        positivePrompt = ComfyUiNodeInput("1", "text")
    )

    @Test
    fun factorySharesJobStoreAndWiresCompletedResultToImageRepository() {
        runBlocking {
            val png = testPng()
            val expectedPng = png.copyOf()
            val expectedPromptId = stablePromptId(request)
            val transport = FakeTransport(png).apply {
                responses.add(
                    ComfyUiApiResponse(200, """{"prompt_id":"$expectedPromptId","number":1}""")
                )
                responses.add(
                    ComfyUiApiResponse(
                        200,
                        """{"$expectedPromptId":{"status":{"status_str":"success","completed":true},"outputs":{"1":{"images":[{"filename":"result.png","subfolder":"","type":"output"}]}}}}"""
                    )
                )
            }
            val jobs = InMemoryComfyUiJobStore()
            val images = RecordingImageRepository()
            val runtime = ComfyUiRuntimeFactory(
                jobs = jobs,
                images = images,
                client = ComfyUiApiClient(transport)
            ).create(config, template, "galaxy-client")

            val promptId = runtime.provider.submit(request)
            assertEquals(expectedPromptId, promptId)
            assertEquals(
                ComfyUiJobRecord(promptId, request.id, request.createdAtEpochMillis),
                jobs.get(promptId)
            )

            val stored = runtime.persistCompletedResult(request, promptId)

            assertEquals("stored-asset", stored.assetId)
            assertEquals(request.id, images.savedRequest?.id)
            assertEquals("image/png", images.savedAsset?.mimeType)
            assertArrayEquals(expectedPng, images.savedBytes!!)
            assertEquals(1, transport.binaryDownloads)
        }
    }

    @Test
    fun createFromSettingsFallsBackToReachableVpnAndUsesItsBearerToken() = runBlocking {
        val transport = FakeTransport(testPng()).apply {
            responses.add(
                ComfyUiApiResponse(
                    200,
                    """{"queue_running":[[0,"prompt-123",{},[],[]]],"queue_pending":[]}"""
                )
            )
        }
        val jobs = InMemoryComfyUiJobStore().apply {
            put(ComfyUiJobRecord("prompt-123", request.id, request.createdAtEpochMillis))
        }
        val factory = ComfyUiRuntimeFactory(
            jobs = jobs,
            images = RecordingImageRepository(),
            client = ComfyUiApiClient(transport)
        )
        val settings = ComfyUiSavedSettings(
            lanBaseUrl = "http://192.168.1.20:8188",
            lanBearerToken = "lan-secret",
            vpnBaseUrl = "https://comfy.example.net",
            vpnBearerToken = "vpn-secret"
        )
        val probed = mutableListOf<String>()
        val selector = ComfyUiEndpointSelector { endpoint ->
            probed += endpoint.normalizedBaseUrl
            if (endpoint.normalizedBaseUrl == "http://192.168.1.20:8188") {
                ComfyUiProbeResult(false, message = "LAN unavailable")
            } else {
                ComfyUiProbeResult(true, 200, "ComfyUI reachable")
            }
        }

        val runtime = factory.createFromSettings(settings, template, "galaxy-client", selector)
        val status = runtime.provider.status("prompt-123")

        assertEquals(ImageGenerationState.RUNNING, status.state)
        assertEquals(
            listOf("http://192.168.1.20:8188", "https://comfy.example.net"),
            probed
        )
        assertEquals(listOf("https://comfy.example.net/queue"), transport.urls)
        assertEquals(listOf("vpn-secret"), transport.bearerTokens)
    }

    @Test
    fun createFromSettingsFailsClosedWhenNoEndpointIsReachable() {
        val factory = ComfyUiRuntimeFactory(
            jobs = InMemoryComfyUiJobStore(),
            images = RecordingImageRepository(),
            client = ComfyUiApiClient(FakeTransport(testPng()))
        )
        val settings = ComfyUiSavedSettings(
            lanBaseUrl = "http://192.168.1.20:8188",
            lanBearerToken = "lan-secret",
            vpnBaseUrl = "https://comfy.example.net",
            vpnBearerToken = "vpn-secret"
        )
        val selector = ComfyUiEndpointSelector {
            ComfyUiProbeResult(false, 503, "ComfyUI unavailable")
        }

        val error = assertThrows(ComfyUiEndpointUnavailableException::class.java) {
            runBlocking {
                factory.createFromSettings(settings, template, "galaxy-client", selector)
            }
        }

        assertFalse(error.message.orEmpty().contains("lan-secret"))
        assertFalse(error.message.orEmpty().contains("vpn-secret"))
    }

    @Test
    fun createFromProviderSettingsUsesTheSavedWorkflowAndEndpoint() = runBlocking {
        val expectedPromptId = stablePromptId(request)
        val transport = FakeTransport(testPng()).apply {
            responses.add(
                ComfyUiApiResponse(200, """{"prompt_id":"$expectedPromptId","number":1}""")
            )
        }
        val jobs = InMemoryComfyUiJobStore()
        val factory = ComfyUiRuntimeFactory(
            jobs = jobs,
            images = RecordingImageRepository(),
            client = ComfyUiApiClient(transport)
        )
        val settings = ProviderSettings(
            comfyUi = ComfyUiSavedSettings(
                lanBaseUrl = "http://192.168.1.20:8188",
                lanBearerToken = "lan-secret"
            ),
            comfyUiWorkflow = template
        )
        val selector = ComfyUiEndpointSelector {
            ComfyUiProbeResult(true, 200, "ComfyUI reachable")
        }

        val runtime = factory.createFromProviderSettings(settings, "galaxy-client", selector)
        val promptId = runtime.provider.submit(request)

        assertEquals("prompt-123", promptId)
        assertEquals(listOf("http://192.168.1.20:8188/prompt"), transport.urls)
        assertEquals(listOf("lan-secret"), transport.bearerTokens)
        assertTrue(transport.requestBodies.single().orEmpty().contains("\"text\":\"A test image\""))
        assertEquals(
            ComfyUiJobRecord(promptId, request.id, request.createdAtEpochMillis),
            jobs.get(promptId)
        )
    }

    @Test
    fun createFromProviderSettingsFailsClosedWhenWorkflowIsMissing() {
        val factory = ComfyUiRuntimeFactory(
            jobs = InMemoryComfyUiJobStore(),
            images = RecordingImageRepository(),
            client = ComfyUiApiClient(FakeTransport(testPng()))
        )
        val settings = ProviderSettings(
            comfyUi = ComfyUiSavedSettings(lanBaseUrl = "http://192.168.1.20:8188")
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                factory.createFromProviderSettings(settings, "galaxy-client")
            }
        }

        assertEquals("ComfyUI workflow template is not configured", error.message)
    }

    private class FakeTransport(
        private val png: ByteArray
    ) : ComfyUiApiTransport {
        val responses = ArrayDeque<ComfyUiApiResponse>()
        val urls = mutableListOf<String>()
        val bearerTokens = mutableListOf<String?>()
        val requestBodies = mutableListOf<String?>()
        var binaryDownloads = 0

        override suspend fun execute(
            method: String,
            url: String,
            body: ByteArray?,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiApiResponse {
            urls += url
            bearerTokens += bearerToken
            requestBodies += body?.toString(Charsets.UTF_8)
            return responses.removeFirst()
        }

        override suspend fun getBinary(
            url: String,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiBinaryResponse {
            binaryDownloads++
            return ComfyUiBinaryResponse(200, "image/png", png)
        }
    }

    private class RecordingImageRepository : GeneratedImageRepository {
        var savedRequest: ImageGenerationRequest? = null
        var savedAsset: com.pitgpt.galaxy.core.model.GeneratedImageAsset? = null
        var savedBytes: ByteArray? = null
        var savedImage: StoredGeneratedImage? = null

        override suspend fun save(
            request: ImageGenerationRequest,
            asset: com.pitgpt.galaxy.core.model.GeneratedImageAsset,
            imageBytes: ByteArray
        ): StoredGeneratedImage {
            savedRequest = request
            savedAsset = asset
            savedBytes = imageBytes.copyOf()
            return StoredGeneratedImage(
                assetId = "stored-asset",
                requestId = request.id,
                conversationId = request.conversationId,
                characterId = request.characterId,
                mimeType = asset.mimeType,
                width = asset.width,
                height = asset.height,
                byteSize = asset.byteSize,
                sha256 = asset.sha256,
                createdAtEpochMillis = asset.createdAtEpochMillis
            ).also { savedImage = it }
        }

        override suspend fun getForRequest(requestId: String): StoredGeneratedImage? =
            savedImage?.takeIf { it.requestId == requestId }

        override suspend fun get(assetId: String): StoredGeneratedImage? = null
        override suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage> = emptyList()
        override suspend fun read(assetId: String): ByteArray = error("Not used by this test")
        override suspend fun delete(assetId: String) = Unit
    }

    private fun stablePromptId(request: ImageGenerationRequest): String =
        UUID.nameUUIDFromBytes(request.id.toByteArray(Charsets.UTF_8)).toString()

    private fun testPng(): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(bytes)
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        bytes[19] = 64
        bytes[23] = 64
        return bytes
    }
}
