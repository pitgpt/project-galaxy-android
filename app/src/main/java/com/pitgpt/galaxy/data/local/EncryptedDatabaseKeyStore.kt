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
    fun getOrCreateDatabaseKey(databaseExists: Boolean, databaseIsPlaintext: Boolean): ByteArray {
        val encoded = preferences.getString(WRAPPED_KEY, null)
        if (encoded != null) return decrypt(encoded)

        check(!databaseExists || databaseIsPlaintext) {
            "The encrypted Galaxy database exists, but its device-bound key is missing."
        }

        val key = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val payload = cipher.iv + cipher.doFinal(key)
        val saved = preferences.edit()
            .putString(WRAPPED_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        if (!saved) {
            key.fill(0)
            error("Could not persist the wrapped Galaxy database key.")
        }
        return key
    }

    private fun decrypt(encoded: String): ByteArray {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_BYTES) { "Wrapped database key is truncated." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingWrappingKey(),
            GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES))
        )
        return cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).also {
            check(it.size == DATABASE_KEY_BYTES) { "Wrapped database key has an invalid length." }
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
        const val DATABASE_KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
