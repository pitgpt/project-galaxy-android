package com.pitgpt.galaxy.data.media

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun failedAtomicPromotionRemovesCiphertextTemporaryFile() {
        val plaintext = "private-image-payload-that-must-never-reach-a-temporary-file".toByteArray()
        var temporaryFile: File? = null
        var temporaryBytes: ByteArray? = null
        val promotionFailure = IOException("simulated atomic promotion failure")
        val failingStore = EncryptedMediaStore(directory, keyProvider, 1024) { source, destination ->
            temporaryFile = source
            temporaryBytes = source.readBytes()
            assertTrue("Ciphertext must be fully written before promotion", source.isFile)
            assertTrue("The temporary file must use the encrypted-media prefix", source.name.startsWith("galaxy-media-"))
            assertFalse("The final asset must not exist before promotion", destination.exists())
            throw promotionFailure
        }

        val actualFailure = assertThrows(IOException::class.java) {
            failingStore.save("asset_13", "image/png", plaintext)
        }

        assertSame("The original promotion failure must propagate", promotionFailure, actualFailure)
        val writtenBytes = requireNotNull(temporaryBytes)
        assertTrue("The temporary payload must include the encrypted header and GCM tag", writtenBytes.size > plaintext.size)
        assertFalse(
            "The temporary file must contain ciphertext, never the plaintext image bytes",
            String(writtenBytes, Charsets.ISO_8859_1).contains(
                String(plaintext, Charsets.ISO_8859_1)
            )
        )
        assertFalse("The failed temporary file must be removed", requireNotNull(temporaryFile).exists())
        assertFalse("A failed promotion must not leave a committed asset", File(directory, "asset_13.gcm").exists())
        assertTrue(
            "No temporary encrypted-media files may remain after a failed promotion",
            directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") }
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
    fun boundedReadZeroizesScratchAndAccumulatorBuffersAfterSuccess() {
        val expected = "encrypted-media-buffer".toByteArray()
        var scratchBuffer: ByteArray? = null
        var consumed = false
        val input = object : InputStream() {
            override fun read(): Int = -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                scratchBuffer = buffer
                if (consumed) return -1
                consumed = true
                expected.copyInto(buffer, offset)
                return expected.size
            }
        }
        val accumulator = ZeroizingByteArrayOutputStream(expected.size)

        val result = EncryptedMediaStore.readBounded(input, expected.size, accumulator)

        assertArrayEquals(expected, result)
        assertTrue("The accumulator backing array must be zeroized", accumulator.isWiped)
        assertTrue(
            "The reusable stream scratch buffer must be zeroized",
            requireNotNull(scratchBuffer).all { it == 0.toByte() }
        )
    }

    @Test
    fun boundedReadZeroizesScratchAndAccumulatorBuffersAfterOversizeFailure() {
        val maximumBytes = 32
        var scratchBuffer: ByteArray? = null
        val input = object : InputStream() {
            override fun read(): Int = -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                scratchBuffer = buffer
                repeat(maximumBytes + 1) { index ->
                    buffer[offset + index] = 0x5a
                }
                return maximumBytes + 1
            }
        }
        val accumulator = ZeroizingByteArrayOutputStream(maximumBytes)

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMediaStore.readBounded(input, maximumBytes, accumulator)
        }

        assertTrue("The accumulator must be zeroized after rejection", accumulator.isWiped)
        assertTrue(
            "The scratch buffer must be zeroized after rejection",
            requireNotNull(scratchBuffer).all { it == 0.toByte() }
        )
    }

    @Test
    fun decryptWipesEncodedCiphertextAndTemporaryCryptoBuffersAfterSuccess() {
        val plaintext = "private decrypted media".toByteArray()
        store.save("asset_14", "image/png", plaintext)
        val encoded = File(directory, "asset_14.gcm").readBytes()

        val decrypted = store.decryptEncoded("asset_14", "image/png", encoded)

        assertArrayEquals(plaintext, decrypted)
        assertTrue("The owned encoded ciphertext buffer must be zeroized", encoded.all { it == 0.toByte() })
    }

    @Test
    fun decryptWipesEncodedCiphertextAfterAuthenticationFailure() {
        store.save("asset_15", "image/png", byteArrayOf(1, 3, 5, 7))
        val encoded = File(directory, "asset_15.gcm").readBytes()
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            store.decryptEncoded("asset_15", "image/png", encoded)
        }

        assertTrue(
            "The owned encoded ciphertext buffer must be zeroized on failure",
            encoded.all { it == 0.toByte() }
        )
    }

    @Test
    fun readRejectsOversizedFileBeforeRequestingKeystoreKey() {
        File(directory, "asset_12.gcm").writeBytes(ByteArray(2048))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            store.read("asset_12", "image/png")
        }

        assertTrue(failure.message?.contains("invalid size") == true)
        assertEquals(0, keyProvider.existingCalls)
        assertEquals(0, keyProvider.createCalls)
    }

    @Test
    fun rejectsMediaLimitsThatWouldOverflowEncryptedFileSizeArithmetic() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMediaStore(directory, keyProvider, maxAssetBytes = Int.MAX_VALUE)
        }
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
        var existingCalls = 0

        override fun getOrCreateKey(): SecretKey {
            createCalls++
            check(available) { "Test key is unavailable." }
            return key
        }

        override fun getExistingKey(): SecretKey {
            existingCalls++
            check(available) { "Test key is unavailable." }
            return key
        }
    }
}
