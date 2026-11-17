package com.pitgpt.galaxy.data.local

import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class EncryptedDatabaseKeyStoreTest {
    private val wrappingKey = SecretKeySpec(
        ByteArray(32) { (it + 1).toByte() },
        "AES"
    )

    @Test
    fun decodedPayloadIsZeroedAfterSuccessfulUnwrap() {
        val expectedDatabaseKey = ByteArray(32) { (it + 17).toByte() }
        val decodedPayload = encodedAndDecodedPayload(expectedDatabaseKey)

        try {
            val actualDatabaseKey = decryptWrappedDatabaseKeyPayload(
                decodedPayload,
                ::decryptPayload
            )
            try {
                assertArrayEquals(expectedDatabaseKey, actualDatabaseKey)
                assertAllZero(decodedPayload)
            } finally {
                actualDatabaseKey.fill(0)
            }
        } finally {
            expectedDatabaseKey.fill(0)
            decodedPayload.fill(0)
        }
    }

    @Test
    fun decodedPayloadIsZeroedWhenGcmAuthenticationFails() {
        val databaseKey = ByteArray(32) { (it + 3).toByte() }
        val decodedPayload = encodedAndDecodedPayload(databaseKey)
        decodedPayload[decodedPayload.lastIndex] =
            (decodedPayload.last().toInt() xor 0x01).toByte()

        try {
            assertThrows(Exception::class.java) {
                decryptWrappedDatabaseKeyPayload(decodedPayload, ::decryptPayload)
            }
            assertAllZero(decodedPayload)
        } finally {
            databaseKey.fill(0)
            decodedPayload.fill(0)
        }
    }

    @Test
    fun decodedPayloadIsZeroedWhenWrappingKeyLookupFails() {
        val databaseKey = ByteArray(32) { (it + 9).toByte() }
        val decodedPayload = encodedAndDecodedPayload(databaseKey)
        val expectedFailure = IllegalStateException("simulated missing Android Keystore key")

        try {
            val actualFailure = assertThrows(IllegalStateException::class.java) {
                decryptWrappedDatabaseKeyPayload(decodedPayload) {
                    throw expectedFailure
                }
            }
            assertTrue(actualFailure === expectedFailure)
            assertAllZero(decodedPayload)
        } finally {
            databaseKey.fill(0)
            decodedPayload.fill(0)
        }
    }

    @Test
    fun rejectedPlaintextKeyAndDecodedPayloadAreZeroedWhenKeyLengthIsInvalid() {
        val invalidDatabaseKey = ByteArray(16) { (it + 5).toByte() }
        val decodedPayload = encodedAndDecodedPayload(invalidDatabaseKey)
        var rejectedPlaintext: ByteArray? = null

        try {
            assertThrows(IllegalStateException::class.java) {
                decryptWrappedDatabaseKeyPayload(decodedPayload) { payload ->
                    decryptPayload(payload).also { rejectedPlaintext = it }
                }
            }
            assertAllZero(decodedPayload)
            assertAllZero(checkNotNull(rejectedPlaintext))
        } finally {
            invalidDatabaseKey.fill(0)
            decodedPayload.fill(0)
            rejectedPlaintext?.fill(0)
        }
    }

    @Test
    fun malformedDecodedPayloadIsZeroedBeforeLengthRejection() {
        val malformedPayload = byteArrayOf(1, 2, 3, 4)

        assertThrows(IllegalArgumentException::class.java) {
            decryptWrappedDatabaseKeyPayload(malformedPayload) {
                error("Decrypt callback must not run for a malformed payload.")
            }
        }

        assertAllZero(malformedPayload)
    }

    private fun encodedAndDecodedPayload(databaseKey: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        var ciphertext: ByteArray? = null
        var combinedPayload: ByteArray? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
            ciphertext = cipher.doFinal(databaseKey)
            combinedPayload = iv + ciphertext
            val encoded = Base64.encodeToString(combinedPayload, Base64.NO_WRAP)
            return Base64.decode(encoded, Base64.NO_WRAP)
        } finally {
            iv.fill(0)
            ciphertext?.fill(0)
            combinedPayload?.fill(0)
        }
    }

    private fun decryptPayload(payload: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey,
            GCMParameterSpec(128, payload, 0, 12)
        )
        return cipher.doFinal(payload, 12, payload.size - 12)
    }

    private fun assertAllZero(bytes: ByteArray) {
        assertTrue("Expected the temporary buffer to be zeroed", bytes.all { it == 0.toByte() })
    }
}
