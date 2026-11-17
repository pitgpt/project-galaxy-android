package com.pitgpt.galaxy.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.StoredGeneratedImage
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedImageHistoryApplicationIntegrationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun productionGenerationPanelOpensEncryptedHistoryAndReturnsWithoutChangingCiphertext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as GalaxyApplication
        val repository = application.generatedImages
        val conversationId = "history-integration-${UUID.randomUUID()}"
        val request = ImageGenerationRequest(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            characterId = conversationId,
            prompt = "Synthetic encrypted history navigation regression",
            width = 64,
            height = 64,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        val plaintext = createPng()
        val asset = GeneratedImageAsset(
            assetId = "provider-${UUID.randomUUID()}",
            requestId = request.id,
            mimeType = "image/png",
            width = 64,
            height = 64,
            byteSize = plaintext.size.toLong(),
            sha256 = sha256(plaintext),
            createdAtEpochMillis = request.createdAtEpochMillis
        )

        var stored: StoredGeneratedImage? = null
        var ciphertextBeforeOpen: ByteArray? = null
        val encryptedFile: File? = null

        try {
            stored = repository.save(request, asset, plaintext)
            val mediaDirectory = File(context.filesDir, EncryptedMediaStore.MEDIA_DIRECTORY)
            val ciphertextFile = File(mediaDirectory, "${stored.assetId}.gcm")
            assertTrue("The synthetic result must be stored as encrypted app-private media", ciphertextFile.isFile)
            ciphertextBeforeOpen = ciphertextFile.readBytes()
            assertFalse(
                "The encrypted media file must not contain the original PNG payload",
                containsSubsequence(ciphertextBeforeOpen, plaintext)
            )

            composeRule.setContent {
                ImageGenerationPanel(
                    app = application,
                    characterId = conversationId,
                    modifier = Modifier.fillMaxSize(),
                    onSubmittingChanged = {}
                )
            }

            composeRule.onNodeWithText("ИСТОРИЯ").assertExists().performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("ИСТОРИЯ ИЗОБРАЖЕНИЙ")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("ИСТОРИЯ ИЗОБРАЖЕНИЙ").assertExists()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("ОТКРЫТЬ")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("ОТКРЫТЬ").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithContentDescription("Предпросмотр сохранённого изображения")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("Предпросмотр сохранённого изображения").assertExists()

            composeRule.onNodeWithText("ЗАКРЫТЬ ПРЕДПРОСМОТР").performClick()
            composeRule.onNodeWithContentDescription("Предпросмотр сохранённого изображения").assertDoesNotExist()

            composeRule.onNodeWithText("‹  НАЗАД").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("ГЕНЕРАЦИЯ ИЗОБРАЖЕНИЯ")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            val persisted = repository.get(requireNotNull(stored).assetId)
            assertEquals("Returning from history must not remove its SQLCipher metadata", stored, persisted)
            assertArrayEquals(
                "Opening and closing the preview must not rewrite encrypted media",
                ciphertextBeforeOpen,
                ciphertextFile.readBytes()
            )
            assertTrue("The encrypted asset must remain present after navigation", ciphertextFile.isFile)
        } finally {
            composeRule.setContent {}
            composeRule.waitForIdle()
            stored?.let { repository.delete(it.assetId) }
            plaintext.fill(0)
            ciphertextBeforeOpen?.fill(0)
        }
    }

    private fun createPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { start ->
            needle.indices.all { offset -> haystack[start + offset] == needle[offset] }
        }
    }
}
