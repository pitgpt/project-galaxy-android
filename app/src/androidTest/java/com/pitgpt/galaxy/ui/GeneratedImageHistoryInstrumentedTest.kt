package com.pitgpt.galaxy.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.core.repository.GeneratedImageRepository
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedImageHistoryInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clearingGeneratedPreviewErasesPixelsWithoutRecyclingComposeBitmap() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            clearGeneratedImagePreview(bitmap.asImageBitmap())
            assertEquals(android.graphics.Color.TRANSPARENT, bitmap.getPixel(0, 0))
            assertTrue(!bitmap.isRecycled)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun historyLoadsMetadataWithoutReadingImageUntilOpenAndClearsPlaintextAfterPreview() {
        val plaintext = createPng()
        val repository = FakeGeneratedImageRepository(
            images = listOf(
                StoredGeneratedImage(
                    assetId = "history-asset",
                    requestId = "history-request",
                    conversationId = "character-a",
                    characterId = "character-a",
                    mimeType = "image/png",
                    width = 2,
                    height = 2,
                    byteSize = plaintext.size.toLong(),
                    sha256 = "a".repeat(64),
                    createdAtEpochMillis = 1L
                )
            ),
            plaintext = plaintext
        )

        composeRule.setContent {
            GeneratedImageHistory(
                repository = repository,
                conversationId = "character-a",
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ИСТОРИЯ ИЗОБРАЖЕНИЙ").assertExists()
        composeRule.onNodeWithText("ОТКРЫТЬ").assertExists()
        assertEquals("History metadata must not decrypt image bytes", 0, repository.readCount.get())

        composeRule.onNodeWithText("ОТКРЫТЬ").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            repository.readCount.get() == 1 && plaintext.all { it == 0.toByte() }
        }

        composeRule.onNodeWithContentDescription("Предпросмотр сохранённого изображения").assertExists()
        composeRule.onNodeWithText("ЗАКРЫТЬ ПРЕДПРОСМОТР").performClick()
        composeRule.onNodeWithContentDescription("Предпросмотр сохранённого изображения").assertDoesNotExist()
    }

    private fun createPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class FakeGeneratedImageRepository(
        private val images: List<StoredGeneratedImage>,
        private val plaintext: ByteArray
    ) : GeneratedImageRepository {
        val readCount = AtomicInteger()

        override suspend fun save(
            request: ImageGenerationRequest,
            asset: GeneratedImageAsset,
            imageBytes: ByteArray
        ): StoredGeneratedImage = error("Not used by history UI")

        override suspend fun getForRequest(requestId: String): StoredGeneratedImage? =
            images.firstOrNull { it.requestId == requestId }

        override suspend fun get(assetId: String): StoredGeneratedImage? =
            images.firstOrNull { it.assetId == assetId }

        override suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage> =
            images.filter { it.conversationId == conversationId }

        override suspend fun read(assetId: String): ByteArray {
            require(images.any { it.assetId == assetId })
            readCount.incrementAndGet()
            return plaintext
        }

        override suspend fun delete(assetId: String) {
            error("Not used by history UI")
        }
    }
}
