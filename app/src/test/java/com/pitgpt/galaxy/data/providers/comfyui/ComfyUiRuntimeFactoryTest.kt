package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
            val transport = FakeTransport(png).apply {
                responses.add(
                    ComfyUiApiResponse(200, """{"prompt_id":"prompt-123","number":1}""")
                )
                responses.add(
                    ComfyUiApiResponse(
                        200,
                        """{"prompt-123":{"status":{"status_str":"success","completed":true},"outputs":{"1":{"images":[{"filename":"result.png","subfolder":"","type":"output"}]}}}}"""
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
            assertEquals("prompt-123", promptId)
            assertEquals(
                ComfyUiJobRecord(promptId, request.id, request.createdAtEpochMillis),
                jobs.get(promptId)
            )

            val stored = runtime.persistCompletedResult(request, promptId)

            assertEquals("stored-asset", stored.assetId)
            assertEquals(request.id, images.savedRequest?.id)
            assertEquals("image/png", images.savedAsset?.mimeType)
            assertArrayEquals(png, images.savedBytes)
            assertEquals(1, transport.binaryDownloads)
        }
    }

    private class FakeTransport(
        private val png: ByteArray
    ) : ComfyUiApiTransport {
        val responses = ArrayDeque<ComfyUiApiResponse>()
        var binaryDownloads = 0

        override suspend fun execute(
            method: String,
            url: String,
            body: ByteArray?,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiApiResponse = responses.removeFirst()

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
            )
        }

        override suspend fun get(assetId: String): StoredGeneratedImage? = null
        override suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage> = emptyList()
        override suspend fun read(assetId: String): ByteArray = error("Not used by this test")
        override suspend fun delete(assetId: String) = Unit
    }

    private fun testPng(): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(bytes)
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        bytes[19] = 64
        bytes[23] = 64
        return bytes
    }
}
