package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationCapabilities
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationStatus
import com.pitgpt.galaxy.core.repository.ImageGenerationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubmitImageGenerationTest {
    private val request = ImageGenerationRequest(
        id = "request-1",
        conversationId = "conversation-1",
        characterId = "lila",
        prompt = "An adult silver-haired fantasy character in a star-lit hall",
        width = 1024,
        height = 1024,
        createdAtEpochMillis = 1L
    )

    @Test
    fun submitsRequestWhenProviderSupportsItsDimensions() = runBlocking {
        var submitted: ImageGenerationRequest? = null
        val useCase = SubmitImageGeneration(FakeProvider(
            supported = capabilities(),
            onSubmit = { submitted = it; "job-123" }
        ))

        assertEquals("job-123", useCase(request))
        assertEquals(request, submitted)
    }

    @Test
    fun rejectsUnsupportedDimensionsBeforeSubmitting() {
        var submitCalled = false
        val useCase = SubmitImageGeneration(FakeProvider(
            supported = capabilities().copy(maxWidth = 768),
            onSubmit = { submitCalled = true; "job-123" }
        ))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCase(request) }
        }
        assertEquals(false, submitCalled)
    }

    @Test
    fun rejectsReferenceImagesWhenProviderDoesNotSupportThem() {
        val requestWithReference = request.copy(referenceAssetIds = listOf("asset-1"))
        var submitCalled = false
        val useCase = SubmitImageGeneration(FakeProvider(
            supported = capabilities().copy(supportsReferenceImages = false),
            onSubmit = { submitCalled = true; "job-123" }
        ))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCase(requestWithReference) }
        }
        assertEquals(false, submitCalled)
    }

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

    private class FakeProvider(
        private val supported: ImageGenerationCapabilities,
        private val onSubmit: (ImageGenerationRequest) -> String
    ) : ImageGenerationProvider {
        override suspend fun capabilities() = supported
        override suspend fun submit(request: ImageGenerationRequest) = onSubmit(request)
        override suspend fun status(providerJobId: String) = ImageGenerationStatus(
            state = com.pitgpt.galaxy.core.model.ImageGenerationState.QUEUED
        )
        override suspend fun cancel(providerJobId: String) = Unit
        override suspend fun result(providerJobId: String) = GeneratedImageAsset(
            assetId = "asset-1",
            requestId = "request-1",
            mimeType = "image/png",
            width = 1024,
            height = 1024,
            byteSize = 1,
            sha256 = "0".repeat(64),
            createdAtEpochMillis = 1L
        )
    }
}
