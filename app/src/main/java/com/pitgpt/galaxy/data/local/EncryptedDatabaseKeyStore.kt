package com.pitgpt.galaxy.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val DATABASE_KEY_BYTES = 32
private const val WRAPPING_KEY_BITS = 256
private const val IV_BYTES = 12
private const val GCM_TAG_BYTES = 16
private const val TAG_BITS = 128

/**
 * Stores a random SQLCipher database key wrapped by a device-bound Android Keystore key.
 *
 * The database key is never regenerated when a database file already exists but the wrapped
 * key is missing or unreadable. That state is surfaced as an error so existing data is retained.
 */
internal class EncryptedDatabaseKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun getOrCreateDatabaseKey(
        databaseExists: Boolean,
        databaseIsPlaintext: Boolean,
        encryptedTemporaryExists: Boolean
    ): ByteArray {
        val encoded = preferences.getString(WRAPPED_KEY, null)
        if (encoded != null) return decrypt(encoded)

        requireDatabaseKeyCreationAllowed(databaseExists, databaseIsPlaintext, encryptedTemporaryExists)

        val key = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            val payload = cipher.iv + cipher.doFinal(key)
            val saved = preferences.edit()
                .putString(WRAPPED_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
            check(saved) { "Could not persist the wrapped Galaxy database key." }
            return key
        } catch (failure: Exception) {
            key.fill(0)
            throw failure
        }
    }

    private fun decrypt(encoded: String): ByteArray {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        return decryptWrappedDatabaseKeyPayload(payload) { iv, ciphertext ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingWrappingKey(),
                GCMParameterSpec(TAG_BITS, iv)
            )
            cipher.doFinal(ciphertext)
        }
    }

    private fun existingWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("The Android Keystore key for Galaxy database encryption is missing.")
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("The Galaxy database wrapping key is unavailable.")
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(WRAPPING_KEY_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "galaxy_database_key"
        const val WRAPPED_KEY = "wrapped_database_key_v1"
        const val KEY_ALIAS = "galaxy_database_key_wrap_aes"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Decrypts an Android Keystore-wrapped database-key payload.
 *
 * Ownership of [payload] is transferred to this function. The decoded payload and the temporary
 * IV/ciphertext slices are zeroed on every exit path. A successfully returned key is transferred
 * to the caller. If decryption succeeds but key-length validation fails, the rejected plaintext
 * key is zeroed too. The decrypt callback must not retain either slice after it returns.
 */
internal fun decryptWrappedDatabaseKeyPayload(
    payload: ByteArray,
    decrypt: (iv: ByteArray, ciphertext: ByteArray) -> ByteArray
): ByteArray {
    var iv: ByteArray? = null
    var ciphertext: ByteArray? = null
    var decryptedKey: ByteArray? = null
    try {
        require(payload.size > IV_BYTES + GCM_TAG_BYTES) {
            "Wrapped database key is truncated."
        }
        val ivBytes = payload.copyOfRange(0, IV_BYTES).also { iv = it }
        val ciphertextBytes = payload.copyOfRange(IV_BYTES, payload.size).also { ciphertext = it }
        val key = decrypt(ivBytes, ciphertextBytes)
        decryptedKey = key
        check(key.size == DATABASE_KEY_BYTES) {
            "Wrapped database key has an invalid length."
        }
        decryptedKey = null
        return key
    } finally {
        decryptedKey?.fill(0)
        ciphertext?.fill(0)
        iv?.fill(0)
        payload.fill(0)
    }
}

/**
 * A missing wrapping key is recoverable only while the canonical source is plaintext,
 * or on a fresh install. An encrypted temporary candidate may be the only recoverable
 * database after a crash between preserving the plaintext backup and promotion.
 */
internal fun requireDatabaseKeyCreationAllowed(
    databaseExists: Boolean,
    databaseIsPlaintext: Boolean,
    encryptedTemporaryExists: Boolean
) {
    check(!databaseExists || databaseIsPlaintext) {
        "The encrypted Galaxy database exists, but its device-bound key is missing."
    }
    check(!encryptedTemporaryExists || (databaseExists && databaseIsPlaintext)) {
        "An encrypted Galaxy migration candidate exists, but its device-bound key is missing."
    }
}
