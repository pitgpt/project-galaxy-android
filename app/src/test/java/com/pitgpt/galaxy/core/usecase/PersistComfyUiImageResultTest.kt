package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.GeneratedImagePayload
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import com.pitgpt.galaxy.core.repository.GeneratedImageResultSource
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistGeneratedImageResultTest {
    private val request = ImageGenerationRequest(
        id = "request-1",
        conversationId = "conversation-1",
        characterId = "character-1",
        prompt = "A test image",
        width = 64,
        height = 64,
        createdAtEpochMillis = 100L
    )
    private val bytes = ByteArray(64) { (it * 7).toByte() }
    private val validAsset = GeneratedImageAsset(
        assetId = "provider-asset",
        requestId = request.id,
        mimeType = "image/png",
        width = 64,
        height = 64,
        byteSize = bytes.size.toLong(),
        sha256 = sha256(bytes),
        createdAtEpochMillis = 200L
    )

    @Test
    fun persistsValidatedBytesAndReturnsGalaxyOwnedMetadata() = runBlocking {
        val source = FakeSource(GeneratedImagePayload(
            validAsset, "image/png", bytes, sha256(bytes)
        ))
        val repository = FakeRepository()
        val saved = PersistGeneratedImageResult(source, repository)(request, "prompt-1")

        assertEquals("prompt-1", source.requestedJobId)
        assertEquals(request.id, repository.savedRequest?.id)
        assertTrue(repository.savedBytes!!.contentEquals(bytes))
        assertEquals("galaxy-asset-1", saved.assetId)
    }

    @Test
    fun returnsExistingRequestResultWithoutDownloadingAgain() = runBlocking {
        val source = FakeSource(GeneratedImagePayload(
            validAsset, "image/png", bytes, sha256(bytes)
        ))
        val repository = FakeRepository()
        val useCase = PersistGeneratedImageResult(source, repository)

        val (first, second) = coroutineScope {
            val firstCall = async { useCase(request, "prompt-1") }
            val secondCall = async { useCase(request, "prompt-1") }
            firstCall.await() to secondCall.await()
        }

        assertEquals(first, second)
        assertEquals(1, source.downloadCalls)
        assertEquals(1, repository.saveCalls)
    }

    @Test
    fun rejectsWrongRequestBeforeRepositoryWrite() = runBlocking {
        val source = FakeSource(GeneratedImagePayload(
            validAsset.copy(requestId = "other-request"), "image/png", bytes, sha256(bytes)
        ))
        val repository = FakeRepository()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { PersistGeneratedImageResult(source, repository)(request, "prompt-1") }
        }
        assertEquals(null, repository.savedRequest)
    }

    @Test
    fun rejectsMismatchedDownloadChecksumBeforeRepositoryWrite() = runBlocking {
        val source = FakeSource(GeneratedImagePayload(
            validAsset, "image/png", bytes, "0".repeat(64)
        ))
        val repository = FakeRepository()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { PersistGeneratedImageResult(source, repository)(request, "prompt-1") }
        }
        assertEquals(null, repository.savedRequest)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class FakeSource(
        private val result: GeneratedImagePayload
    ) : GeneratedImageResultSource {
        var requestedJobId: String? = null
        var downloadCalls: Int = 0
        override suspend fun completedImage(providerJobId: String): GeneratedImagePayload {
            requestedJobId = providerJobId
            downloadCalls++
            return result
        }
    }

    private class FakeRepository : GeneratedImageRepository {
        var savedRequest: ImageGenerationRequest? = null
        var savedBytes: ByteArray? = null
        var savedResult: StoredGeneratedImage? = null
        var saveCalls: Int = 0

        override suspend fun save(
            request: ImageGenerationRequest,
            asset: GeneratedImageAsset,
            imageBytes: ByteArray
        ): StoredGeneratedImage {
            savedRequest = request
            savedBytes = imageBytes.copyOf()
            saveCalls++
            return StoredGeneratedImage(
                assetId = "galaxy-asset-1",
                requestId = request.id,
                conversationId = request.conversationId,
                characterId = request.characterId,
                mimeType = asset.mimeType,
                width = asset.width,
                height = asset.height,
                byteSize = asset.byteSize,
                sha256 = asset.sha256,
                createdAtEpochMillis = asset.createdAtEpochMillis
            ).also { savedResult = it }
        }

        override suspend fun getForRequest(requestId: String): StoredGeneratedImage? =
            savedResult?.takeIf { it.requestId == requestId }

        override suspend fun get(assetId: String): StoredGeneratedImage? = null
        override suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage> = emptyList()
        override suspend fun read(assetId: String): ByteArray = error("Not used in this test")
        override suspend fun delete(assetId: String) = Unit
    }
}
