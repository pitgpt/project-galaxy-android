package com.pitgpt.galaxy.data.media

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileFilter
import java.nio.file.Files
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class EncryptedMediaStoreTest {
    private val directory = Files.createTempDirectory("galaxy-media-test").toFile()
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val keyProvider = FakeKeyProvider(key)
    private val store = EncryptedMediaStore(directory, keyProvider, maxAssetBytes = 1024)

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun savesOnlyCiphertextAndRoundTripsBytes() {
        val plaintext = "private-image-payload-that-must-not-appear-on-disk".toByteArray()
        val metadata = store.save("asset_01", "image/png", plaintext)
        val encryptedFile = File(directory, "asset_01.gcm")

        assertTrue(encryptedFile.isFile)
        assertFalse(String(encryptedFile.readBytes(), Charsets.ISO_8859_1).contains(
            String(plaintext, Charsets.ISO_8859_1)
        ))
        assertArrayEquals(plaintext, store.read("asset_01", "image/png"))
        assertEquals(plaintext.size, metadata.byteSize)
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest(plaintext)
                .joinToString("") { "%02x".format(it) },
            metadata.sha256
        )
    }

    @Test
    fun authenticatedDataBindsAssetIdAndMimeType() {
        store.save("asset_02", "image/png", byteArrayOf(1, 2, 3, 4))

        assertThrows(Exception::class.java) {
            store.read("asset_02", "image/jpeg")
        }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        store.save("asset_03", "image/webp", byteArrayOf(10, 20, 30, 40))
        val file = File(directory, "asset_03.gcm")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertThrows(Exception::class.java) {
            store.read("asset_03", "image/webp")
        }
    }

    @Test
    fun truncatedFileIsRejected() {
        store.save("asset_04", "image/jpeg", byteArrayOf(1, 2, 3))
        File(directory, "asset_04.gcm").writeBytes(byteArrayOf(0x47, 0x4C, 0x58))

        assertThrows(IllegalArgumentException::class.java) {
            store.read("asset_04", "image/jpeg")
        }
    }

    @Test
    fun rejectsUnsafeIdsUnsupportedMimeAndOversizedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            store.save("../outside", "image/png", byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save("asset_05", "text/plain", byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.save("asset_06", "image/png", ByteArray(1025))
        }
    }

    @Test
    fun boundedReadAcceptsAStreamAtTheExactLimit() {
        val expected = ByteArray(64) { it.toByte() }
        val input = ByteArrayInputStream(expected)

        assertArrayEquals(expected, EncryptedMediaStore.readBounded(input, expected.size))
        assertEquals(0, input.available())
    }

    @Test
    fun boundedReadRejectsGrowthWithoutReadingTheEntireStream() {
        val maximumBytes = 64
        val input = ByteArrayInputStream(ByteArray(maximumBytes + 2))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            EncryptedMediaStore.readBounded(input, maximumBytes)
        }

        assertTrue(failure.message?.contains("size limit") == true)
        assertEquals(
            "The reader may consume only the configured limit plus one detection byte",
            1,
            input.available()
        )
    }

    @Test
    fun deleteRemovesEncryptedAssetAndIsIdempotentlyObservable() {
        store.save("asset_07", "image/png", byteArrayOf(5, 6, 7))

        assertTrue(store.contains("asset_07"))
        assertTrue(store.delete("asset_07"))
        assertFalse(store.contains("asset_07"))
        assertFalse(store.delete("asset_07"))
    }

    @Test
    fun refusesToCreateAReplacementKeyWhenDirectoryCannotBeInspected() {
        val unreadableDirectory = object : File(directory.path) {
            override fun listFiles(filter: FileFilter?): Array<File>? = null
        }
        val guardedStore = EncryptedMediaStore(unreadableDirectory, keyProvider, maxAssetBytes = 1024)

        assertThrows(IllegalStateException::class.java) {
            guardedStore.save("asset_11", "image/png", byteArrayOf(1, 2, 3))
        }
        assertEquals(0, keyProvider.createCalls)
    }

    @Test
    fun missingKeyOnReadDoesNotAttemptToCreateReplacement() {
        store.save("asset_08", "image/png", byteArrayOf(8, 9))
        keyProvider.available = false

        assertThrows(IllegalStateException::class.java) {
            store.read("asset_08", "image/png")
        }
        assertEquals(1, keyProvider.createCalls)
    }

    @Test
    fun missingKeyCannotBeReplacedWhileEncryptedAssetsExist() {
        store.save("asset_09", "image/png", byteArrayOf(9, 10))
        keyProvider.available = false

        assertThrows(IllegalStateException::class.java) {
            store.save("asset_10", "image/png", byteArrayOf(10, 11))
        }
        assertEquals(1, keyProvider.createCalls)
        assertFalse(store.contains("asset_10"))
    }

    private class FakeKeyProvider(
        private val key: SecretKey
    ) : MediaEncryptionKeyProvider {
        var available = true
        var createCalls = 0

        override fun getOrCreateKey(): SecretKey {
            createCalls++
            check(available) { "Test key is unavailable." }
            return key
        }

        override fun getExistingKey(): SecretKey {
            check(available) { "Test key is unavailable." }
            return key
        }
    }
}
