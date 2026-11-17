package com.pitgpt.galaxy.data.repository

import androidx.room.Room
import com.pitgpt.galaxy.core.model.GeneratedImageAsset
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.repository.GeneratedImageReadException
import com.pitgpt.galaxy.core.repository.GeneratedImageReadFailure
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import com.pitgpt.galaxy.data.local.dao.GeneratedImageDao
import com.pitgpt.galaxy.data.local.entity.GeneratedImageEntity
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import com.pitgpt.galaxy.data.media.MediaEncryptionKeyProvider
import com.pitgpt.galaxy.data.media.MediaEncryptionKeyUnavailableException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
    private lateinit var keyProvider: FakeKeyProvider
    private lateinit var repository: RoomGeneratedImageRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, GalaxyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mediaDirectory = Files.createTempDirectory("galaxy-generated-images").toFile()
        keyProvider = FakeKeyProvider()
        mediaStore = EncryptedMediaStore(mediaDirectory, keyProvider, maxAssetBytes = 1024)
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
    fun reportsMissingMetadataSeparately() = runBlocking {
        val failure = runCatching {
            repository.read(UUID.randomUUID().toString())
        }.exceptionOrNull()

        assertEquals(
            GeneratedImageReadFailure.METADATA_MISSING,
            (failure as? GeneratedImageReadException)?.failure
        )
    }

    @Test
    fun reportsDatabaseReadFailureAsStorageUnavailable() = runBlocking {
        val failingRepository = RoomGeneratedImageRepository(
            failingDao(
                insertAction = {},
                getAction = { throw IllegalStateException("database unavailable") }
            ),
            mediaStore
        )

        val failure = runCatching {
            failingRepository.read(UUID.randomUUID().toString())
        }.exceptionOrNull()

        assertEquals(
            GeneratedImageReadFailure.STORAGE_UNAVAILABLE,
            (failure as? GeneratedImageReadException)?.failure
        )
    }

    @Test
    fun reportsMissingCiphertextAndPreservesMetadata() = runBlocking {
        val request = request("conversation-missing-ciphertext")
        val bytes = ByteArray(40) { (it + 1).toByte() }
        val saved = repository.save(request, asset(request.id, bytes), bytes)
        val ciphertext = File(mediaDirectory, "${saved.assetId}.gcm")
        assertTrue(ciphertext.delete())

        val failure = runCatching { repository.read(saved.assetId) }.exceptionOrNull()

        assertEquals(
            GeneratedImageReadFailure.CIPHERTEXT_MISSING,
            (failure as? GeneratedImageReadException)?.failure
        )
        assertEquals("Metadata must remain available for explicit recovery", saved, repository.get(saved.assetId))
    }

    @Test
    fun reportsUnavailableKeyWithoutReplacingItOrDeletingCiphertext() = runBlocking {
        val request = request("conversation-key-unavailable")
        val bytes = ByteArray(40) { (it + 2).toByte() }
        val saved = repository.save(request, asset(request.id, bytes), bytes)
        val ciphertext = File(mediaDirectory, "${saved.assetId}.gcm")
        val originalCiphertext = ciphertext.readBytes()
        keyProvider.available = false

        val failure = runCatching { repository.read(saved.assetId) }.exceptionOrNull()

        assertEquals(
            GeneratedImageReadFailure.KEY_UNAVAILABLE,
            (failure as? GeneratedImageReadException)?.failure
        )
        assertEquals(1, keyProvider.createCalls)
        assertTrue(ciphertext.isFile)
        assertArrayEquals(originalCiphertext, ciphertext.readBytes())
        assertEquals(saved, repository.get(saved.assetId))
    }

    @Test
    fun reportsTamperedCiphertextWithoutDeletingTheAsset() = runBlocking {
        val request = request("conversation-corrupt-ciphertext")
        val bytes = ByteArray(40) { (it + 3).toByte() }
        val saved = repository.save(request, asset(request.id, bytes), bytes)
        val ciphertext = File(mediaDirectory, "${saved.assetId}.gcm")
        val tampered = ciphertext.readBytes()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
        ciphertext.writeBytes(tampered)

        val failure = runCatching { repository.read(saved.assetId) }.exceptionOrNull()

        assertEquals(
            GeneratedImageReadFailure.CORRUPT_CIPHERTEXT,
            (failure as? GeneratedImageReadException)?.failure
        )
        assertTrue(ciphertext.isFile)
        assertEquals(saved, repository.get(saved.assetId))
    }

    @Test
    fun reportsMetadataIntegrityMismatchWithoutDeletingCiphertext() = runBlocking {
        val request = request("conversation-integrity-mismatch")
        val bytes = ByteArray(40) { (it + 4).toByte() }
        val saved = repository.save(request, asset(request.id, bytes), bytes)
        val delegate = database.generatedImageDao()
        val mismatchedDao = object : GeneratedImageDao by delegate {
            override suspend fun get(assetId: String): GeneratedImageEntity? =
                delegate.get(assetId)?.copy(sha256 = "0".repeat(64))
        }
        val mismatchedRepository = RoomGeneratedImageRepository(mismatchedDao, mediaStore)

        val failure = runCatching { mismatchedRepository.read(saved.assetId) }.exceptionOrNull()

        assertEquals(
            GeneratedImageReadFailure.INTEGRITY_MISMATCH,
            (failure as? GeneratedImageReadException)?.failure
        )
        assertTrue(mediaStore.contains(saved.assetId))
        assertEquals(saved, repository.get(saved.assetId))
    }

    @Test
    fun clearsDecryptedBytesWhenStoredSizeDoesNotMatchMetadata() {
        val bytes = ByteArray(16) { (it + 1).toByte() }

        assertThrows(IllegalStateException::class.java) {
            validateDecryptedGeneratedImageBytes(bytes, bytes.size.toLong() + 1, "0".repeat(64))
        }

        assertArrayEquals(ByteArray(bytes.size), bytes)
    }

    @Test
    fun clearsDecryptedBytesWhenStoredChecksumDoesNotMatchMetadata() {
        val bytes = ByteArray(16) { (it + 1).toByte() }

        assertThrows(IllegalStateException::class.java) {
            validateDecryptedGeneratedImageBytes(bytes, bytes.size.toLong(), "0".repeat(64))
        }

        assertArrayEquals(ByteArray(bytes.size), bytes)
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
    fun conflictingResultForSameRequestDoesNotReplaceOriginalCiphertext() = runBlocking {
        val request = request("conversation-a")
        val originalBytes = ByteArray(48) { (it * 5).toByte() }
        val differentBytes = ByteArray(48) { (it * 7).toByte() }
        val original = repository.save(request, asset(request.id, originalBytes), originalBytes)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.save(request, asset(request.id, differentBytes), differentBytes)
            }
        }

        assertArrayEquals(originalBytes, repository.read(original.assetId))
        assertEquals(1, mediaDirectory.listFiles { file -> file.extension == "gcm" }?.size)
    }

    @Test
    fun preservesCiphertextWhenMetadataReadBackFails() = runBlocking {
        val insertFailure = IllegalStateException("metadata insert failed")
        val readBackFailure = IllegalStateException("metadata read-back failed")
        val failingRepository = RoomGeneratedImageRepository(
            failingDao(
                insertAction = { throw insertFailure },
                getAction = { throw readBackFailure }
            ),
            mediaStore
        )
        val request = request("conversation-readback-failure")
        val bytes = ByteArray(40) { (it + 3).toByte() }

        val failure = runCatching {
            failingRepository.save(request, asset(request.id, bytes), bytes)
        }.exceptionOrNull()

        assertEquals("metadata insert failed", failure?.message)
        assertTrue(
            "A failed metadata read-back must be attached to the original insert failure",
            failure?.suppressed?.any { it.message == readBackFailure.message } == true
        )
        val encryptedFile = mediaDirectory.listFiles { file -> file.extension == "gcm" }!!.single()
        assertArrayEquals(
            bytes,
            mediaStore.read(encryptedFile.nameWithoutExtension, "image/png")
        )
    }

    @Test
    fun deletesCiphertextWhenMetadataReadBackConfirmsNoRow() = runBlocking {
        val insertFailure = IllegalStateException("metadata insert failed")
        val failingRepository = RoomGeneratedImageRepository(
            failingDao(
                insertAction = { throw insertFailure },
                getAction = { null }
            ),
            mediaStore
        )
        val request = request("conversation-metadata-absent")
        val bytes = ByteArray(40) { (it + 5).toByte() }

        val failure = runCatching {
            failingRepository.save(request, asset(request.id, bytes), bytes)
        }.exceptionOrNull()

        assertEquals("metadata insert failed", failure?.message)
        assertTrue(mediaDirectory.listFiles { file -> file.extension == "gcm" }.orEmpty().isEmpty())
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
            val failure = runCatching { repository.read(saved.assetId) }.exceptionOrNull()
            assertEquals(
                GeneratedImageReadFailure.METADATA_MISSING,
                (failure as? GeneratedImageReadException)?.failure
            )
        }
    }

    @Test
    fun encryptedMediaFileOperationsUseTheInjectedIoDispatcher() = runBlocking {
        val dispatcher = RecordingDispatcher()
        try {
            val ioRepository = RoomGeneratedImageRepository(database.generatedImageDao(), mediaStore, dispatcher)
            val request = request("conversation-io")
            val bytes = ByteArray(48) { (it + 1).toByte() }

            val saved = ioRepository.save(request, asset(request.id, bytes), bytes)
            assertArrayEquals(bytes, ioRepository.read(saved.assetId))
            ioRepository.delete(saved.assetId)

            assertTrue(dispatcher.threadNames.size >= 3)
            assertTrue(dispatcher.threadNames.all { it == RecordingDispatcher.THREAD_NAME })
        } finally {
            dispatcher.close()
        }
    }

    private fun failingDao(
        insertAction: suspend (GeneratedImageEntity) -> Unit,
        getAction: suspend (String) -> GeneratedImageEntity?
    ): GeneratedImageDao = object : GeneratedImageDao {
        override suspend fun insert(entity: GeneratedImageEntity) = insertAction(entity)
        override suspend fun get(assetId: String) = getAction(assetId)
        override suspend fun getForRequest(requestId: String): GeneratedImageEntity? = null
        override suspend fun listForConversation(conversationId: String): List<GeneratedImageEntity> = emptyList()
        override suspend fun delete(assetId: String): Int = 0
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
        var available = true
        var createCalls = 0

        override fun getOrCreateKey(): SecretKey {
            createCalls++
            return key
        }

        override fun getExistingKey(): SecretKey {
            if (!available) throw MediaEncryptionKeyUnavailableException()
            return key
        }
    }

    private class RecordingDispatcher : CoroutineDispatcher() {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME)
        }
        val threadNames = CopyOnWriteArrayList<String>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executor.execute {
                threadNames += Thread.currentThread().name
                block.run()
            }
        }

        fun close() {
            executor.shutdown()
        }

        companion object {
            const val THREAD_NAME = "galaxy-media-io-test"
        }
    }
}
