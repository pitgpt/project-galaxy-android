package com.pitgpt.galaxy.data.repository

import androidx.room.Room
import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import com.pitgpt.galaxy.data.media.MediaEncryptionKeyProvider
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RoomGeneratedImageRepositoryTest {
    private lateinit var database: GalaxyDatabase
    private lateinit var mediaDirectory: java.io.File
    private lateinit var mediaStore: EncryptedMediaStore
    private lateinit var repository: RoomGeneratedImageRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, GalaxyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaDirectory = Files.createTempDirectory("galaxy-generated-images").toFile()
        mediaStore = EncryptedMediaStore(mediaDirectory, FakeKeyProvider(), maxAssetBytes = 1024)
        repository = RoomGeneratedImageRepository(database.generatedImageDao(), mediaStore)
    }

    @After
    fun tearDown() {
        database.close()
        mediaDirectory.deleteRecursively()
    }

    @Test
    fun savesEncryptedBytesAndMetadataThenReadsBack() = runBlocking {
        val request = request("conversation-a")
        val bytes = ByteArray(128) { (it * 3).toByte() }
        val saved = repository.save(request, asset(request.id, bytes), bytes)

        assertTrue(mediaStore.contains(saved.assetId))
        assertEquals(request.id, saved.requestId)
        assertEquals(request.conversationId, saved.conversationId)
        assertEquals(request.characterId, saved.characterId)
        assertArrayEquals(bytes, repository.read(saved.assetId))
        assertEquals(saved, repository.get(saved.assetId))
    }

    @Test
    fun repeatedSaveForSameRequestReturnsExistingEncryptedAsset() = runBlocking {
        val request = request("conversation-a")
        val bytes = ByteArray(48) { (it * 5).toByte() }

        val first = repository.save(request, asset(request.id, bytes), bytes)
        val second = repository.save(request, asset(request.id, bytes), bytes)

        assertEquals(first, second)
        assertEquals(first, repository.getForRequest(request.id))
        assertEquals(1, mediaDirectory.listFiles { file -> file.extension == "gcm" }?.size)
        assertArrayEquals(bytes, repository.read(first.assetId))
    }

    @Test
    fun listsOnlyImagesForRequestedConversation() = runBlocking {
        val first = request("conversation-a")
        val second = request("conversation-b")
        val firstBytes = ByteArray(20) { 1 }
        val secondBytes = ByteArray(24) { 2 }
        val savedFirst = repository.save(first, asset(first.id, firstBytes), firstBytes)
        repository.save(second, asset(second.id, secondBytes), secondBytes)

        assertEquals(listOf(savedFirst), repository.listForConversation("conversation-a"))
        assertTrue(repository.listForConversation("unknown").isEmpty())
    }

    @Test
    fun rejectsMismatchedRequestAndChecksumBeforeWriting() = runBlocking {
        val request = request("conversation-a")
        val bytes = ByteArray(32) { 7 }
        val wrongRequest = asset("another-request", bytes)
        val wrongChecksum = asset(request.id, bytes).copy(sha256 = "0".repeat(64))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.save(request, wrongRequest, bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.save(request, wrongChecksum, bytes) }
        }
        assertTrue(mediaDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(repository.listForConversation("conversation-a").isEmpty())
    }

    @Test
    fun deleteRemovesMetadataAndCiphertext() {
        runBlocking {
            val request = request("conversation-a")
            val bytes = ByteArray(40) { 9 }
            val saved = repository.save(request, asset(request.id, bytes), bytes)

            repository.delete(saved.assetId)

            assertNull(repository.get(saved.assetId))
            assertFalse(mediaStore.contains(saved.assetId))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.read(saved.assetId) }
            }
        }
    }

    private fun request(conversationId: String) = ImageGenerationRequest(
        id = "request-${conversationId}",
        conversationId = conversationId,
        characterId = "character-a",
        prompt = "A test image",
        width = 64,
        height = 64,
        createdAtEpochMillis = 1234L
    )

    private fun asset(requestId: String, bytes: ByteArray) = GeneratedImageAsset(
        assetId = "provider-owned-id",
        requestId = requestId,
        mimeType = "image/png",
        width = 64,
        height = 64,
        byteSize = bytes.size.toLong(),
        sha256 = sha256(bytes),
        createdAtEpochMillis = 5678L
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class FakeKeyProvider : MediaEncryptionKeyProvider {
        private val key: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun getOrCreateKey(): SecretKey = key
        override fun getExistingKey(): SecretKey = key
    }
}
