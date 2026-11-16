package com.pitgpt.galaxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import com.pitgpt.galaxy.data.repository.RoomGeneratedImageRepository
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalaxyApplicationGeneratedImageStorageInstrumentedTest {
    @Test
    fun productionGeneratedImageRepositoryUsesEncryptedMediaAndSqlCipherMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as GalaxyApplication
        val repository = application.generatedImages

        assertEquals(
            "The production composition root must bind generated images to the Room repository",
            RoomGeneratedImageRepository::class.java,
            repository.javaClass
        )
        assertEquals(
            "The production media binding must use the encrypted media store",
            EncryptedMediaStore::class.java,
            application.mediaStore.javaClass
        )

        val request = ImageGenerationRequest(
            id = UUID.randomUUID().toString(),
            conversationId = "instrumented-${UUID.randomUUID()}",
            characterId = "character-${UUID.randomUUID()}",
            prompt = "Synthetic production encrypted-media wiring regression",
            width = 64,
            height = 64,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        val imageBytes = ByteArray(128) { ((it * 73 + 19) % 256).toByte() }
        val asset = GeneratedImageAsset(
            assetId = "provider-${UUID.randomUUID()}",
            requestId = request.id,
            mimeType = "image/png",
            width = 64,
            height = 64,
            byteSize = imageBytes.size.toLong(),
            sha256 = sha256(imageBytes),
            createdAtEpochMillis = request.createdAtEpochMillis
        )

        val stored = repository.save(request, asset, imageBytes)
        val mediaDirectory = File(context.filesDir, EncryptedMediaStore.MEDIA_DIRECTORY)
        val encryptedFile = File(mediaDirectory, "${stored.assetId}.gcm")
        try {
            assertTrue("The production media directory must remain under app-private filesDir",
                mediaDirectory.canonicalFile.parentFile == context.filesDir.canonicalFile)
            assertTrue("The production encrypted media store must contain the saved asset",
                application.mediaStore.contains(stored.assetId))
            assertTrue("The generated asset must be written to its encrypted app-private file",
                encryptedFile.isFile)
            assertFalse(
                "The production media file must not contain the original plaintext payload",
                containsSubsequence(encryptedFile.readBytes(), imageBytes)
            )

            assertArrayEquals(imageBytes, repository.read(stored.assetId))
            assertEquals(stored, repository.get(stored.assetId))
            assertEquals(stored, repository.getForRequest(request.id))

            val databaseFile = context.getDatabasePath("galaxy.db")
            assertTrue("The production image metadata database must exist", databaseFile.isFile)
            val plaintextSqliteHeader = "SQLite format 3\\u0000".toByteArray(Charsets.US_ASCII)
            val actualHeader = ByteArray(plaintextSqliteHeader.size)
            FileInputStream(databaseFile).use { input ->
                var offset = 0
                while (offset < actualHeader.size) {
                    val count = input.read(actualHeader, offset, actualHeader.size - offset)
                    assertTrue("The encrypted database header must be readable", count > 0)
                    offset += count
                }
            }
            assertFalse(
                "Production generated-image metadata must remain in the SQLCipher database",
                actualHeader.contentEquals(plaintextSqliteHeader)
            )
        } finally {
            repository.delete(stored.assetId)
        }

        assertFalse("The synthetic encrypted media file must be removed after the test",
            encryptedFile.exists())
        assertNull("The synthetic metadata row must be removed after the test",
            repository.get(stored.assetId))
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
