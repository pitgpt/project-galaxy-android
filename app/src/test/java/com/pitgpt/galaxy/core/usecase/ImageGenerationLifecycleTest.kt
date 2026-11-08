package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationCapabilities
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.model.ImageGenerationStatus
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageGenerationLifecycleTest {
    @Test
    fun retrievesCurrentProviderStatus() = runBlocking {
        val expected = ImageGenerationStatus(ImageGenerationState.RUNNING, progressPercent = 45)
        val provider = FakeProvider(status = expected)

        assertEquals(expected, GetImageGenerationStatus(provider)("job-1"))
        assertEquals("job-1", provider.lastStatusJobId)
    }

    @Test
    fun rejectsBlankJobIdBeforeCallingProvider() {
        val provider = FakeProvider()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { GetImageGenerationStatus(provider)(" ") }
        }
        assertEquals(null, provider.lastStatusJobId)
    }

    @Test
    fun cancelsQueuedJobWhenProviderSupportsCancellation() = runBlocking {
        val provider = FakeProvider(
            capabilities = capabilities().copy(supportsCancellation = true),
            status = ImageGenerationStatus(ImageGenerationState.QUEUED)
        )

        CancelImageGeneration(provider)("job-2")

        assertEquals("job-2", provider.cancelledJobId)
    }

    @Test
    fun cancelsRunningJobWhenProviderSupportsCancellation() = runBlocking {
        val provider = FakeProvider(
            capabilities = capabilities().copy(supportsCancellation = true),
            status = ImageGenerationStatus(ImageGenerationState.RUNNING, progressPercent = 45)
        )

        CancelImageGeneration(provider)("job-running")

        assertEquals("job-running", provider.cancelledJobId)
    }

    @Test
    fun refusesCancellationWhenProviderDoesNotSupportIt() {
        val provider = FakeProvider(
            capabilities = capabilities().copy(supportsCancellation = false),
            status = ImageGenerationStatus(ImageGenerationState.RUNNING)
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { CancelImageGeneration(provider)("job-3") }
        }
        assertEquals(null, provider.cancelledJobId)
    }

    @Test
    fun refusesCancellationOfTerminalJob() {
        val provider = FakeProvider(
            capabilities = capabilities().copy(supportsCancellation = true),
            status = ImageGenerationStatus(ImageGenerationState.COMPLETED)
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { CancelImageGeneration(provider)("job-4") }
        }
        assertEquals(null, provider.cancelledJobId)
    }

    @Test
    fun returnsAssetOnlyAfterCompletionAndMatchesRequest() = runBlocking {
        val provider = FakeProvider(
            status = ImageGenerationStatus(ImageGenerationState.COMPLETED)
        )

        assertEquals("request-1", GetGeneratedImageAsset(provider)("job-5", "request-1").requestId)
    }

    @Test
    fun refusesResultBeforeCompletion() {
        val provider = FakeProvider(
            status = ImageGenerationStatus(ImageGenerationState.RUNNING)
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { GetGeneratedImageAsset(provider)("job-6", "request-1") }
        }
        assertEquals(false, provider.resultCalled)
    }

    @Test
    fun rejectsAssetBelongingToAnotherRequest() {
        val provider = FakeProvider(
            status = ImageGenerationStatus(ImageGenerationState.COMPLETED),
            asset = asset().copy(requestId = "different-request")
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { GetGeneratedImageAsset(provider)("job-7", "request-1") }
        }
    }

    @Test
    fun rejectsAssetWithUnsupportedFormat() {
        val provider = FakeProvider(
            status = ImageGenerationStatus(ImageGenerationState.COMPLETED),
            asset = asset().copy(mimeType = "image/gif")
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { GetGeneratedImageAsset(provider)("job-8", "request-1") }
        }
    }

    @Test
    fun rejectsAssetWithUnsupportedDimensions() {
        val provider = FakeProvider(
            status = ImageGenerationStatus(ImageGenerationState.COMPLETED),
            asset = asset().copy(width = 4096)
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { GetGeneratedImageAsset(provider)("job-9", "request-1") }
        }
    }

    private class FakeProvider(
        private val capabilities: ImageGenerationCapabilities = capabilities(),
        private val status: ImageGenerationStatus = ImageGenerationStatus(ImageGenerationState.QUEUED),
        private val asset: GeneratedImageAsset = asset()
    ) : ImageGenerationProvider {
        var lastStatusJobId: String? = null
        var cancelledJobId: String? = null
        var resultCalled: Boolean = false

        override suspend fun capabilities() = capabilities
        override suspend fun submit(request: ImageGenerationRequest) = "job-created"
        override suspend fun status(providerJobId: String): ImageGenerationStatus {
            lastStatusJobId = providerJobId
            return status
        }
        override suspend fun cancel(providerJobId: String) {
            cancelledJobId = providerJobId
        }
        override suspend fun result(providerJobId: String): GeneratedImageAsset {
            resultCalled = true
            return asset
        }
    }

    companion object {
        private fun capabilities() = ImageGenerationCapabilities(
            supportedMimeTypes = setOf("image/png", "image/webp"),
            minWidth = 256,
            maxWidth = 2048,
            minHeight = 256,
            maxHeight = 2048,
            supportsReferenceImages = true,
            supportsCancellation = true,
            supportsProgress = true
        )

        private fun asset() = GeneratedImageAsset(
            assetId = "asset-1",
            requestId = "request-1",
            mimeType = "image/png",
            width = 1024,
            height = 1024,
            byteSize = 4096,
            sha256 = "a".repeat(64),
            createdAtEpochMillis = 1L
        )
    }
}
