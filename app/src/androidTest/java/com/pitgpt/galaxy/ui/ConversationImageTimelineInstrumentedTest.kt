package com.pitgpt.galaxy.ui

import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class)
class ConversationImageTimelineInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun timelineMetadataDoesNotDecryptUntilOpenAndPlaintextIsCleared() {
        val plaintext = createPng()
        val asset = StoredGeneratedImage(
            assetId = "timeline-asset",
            requestId = "timeline-request",
            conversationId = "character-a",
            characterId = "character-a",
            mimeType = "image/png",
            width = 2,
            height = 2,
            byteSize = plaintext.size.toLong(),
            sha256 = "a".repeat(64),
            createdAtEpochMillis = 1L
        )
        val repository = FakeRepository(asset, plaintext)

        composeRule.setContent {
            GeneratedImageTimelineCard(repository = repository, asset = asset)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ОТКРЫТЬ ИЗОБРАЖЕНИЕ").assertExists()
        assertEquals(0, repository.readCount.get())

        composeRule.onNodeWithText("ОТКРЫТЬ ИЗОБРАЖЕНИЕ").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            repository.readCount.get() == 1 && plaintext.all { it == 0.toByte() }
        }

        composeRule.onNodeWithContentDescription("Изображение из истории разговора").assertExists()
        composeRule.onNodeWithText("ЗАКРЫТЬ ПРЕДПРОСМОТР").performClick()
        composeRule.onNodeWithContentDescription("Изображение из истории разговора").assertDoesNotExist()
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

    private class FakeRepository(
        private val asset: StoredGeneratedImage,
        private val plaintext: ByteArray
    ) : GeneratedImageRepository {
        val readCount = AtomicInteger()

        override suspend fun save(
            request: ImageGenerationRequest,
            asset: GeneratedImageAsset,
            imageBytes: ByteArray
        ): StoredGeneratedImage = error("Not used")

        override suspend fun getForRequest(requestId: String): StoredGeneratedImage? =
            asset.takeIf { it.requestId == requestId }

        override suspend fun get(assetId: String): StoredGeneratedImage? =
            asset.takeIf { it.assetId == assetId }

        override suspend fun listForConversation(conversationId: String): List<StoredGeneratedImage> =
            listOf(asset).filter { it.conversationId == conversationId }

        override suspend fun read(assetId: String): ByteArray {
            require(asset.assetId == assetId)
            readCount.incrementAndGet()
            return plaintext
        }

        override suspend fun delete(assetId: String) {
            error("Not used")
        }
    }
}
