package com.pitgpt.galaxy.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyGenerator
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator as JceKeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores a random SQLCipher passphrase wrapped by a non-exportable Android Keystore key.
 *
 * A missing wrapped key is never silently replaced when a database file already exists:
 * doing so would make existing encrypted user data permanently inaccessible.
 */
class DatabaseKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadOrCreateDatabaseKey(
        databaseAlreadyExists: Boolean,
        allowExistingPlaintextDatabase: Boolean = false
    ): ByteArray {
        val encoded = preferences.getString(WRAPPED_KEY, null)
        if (encoded != null) return unwrapDatabaseKey(encoded)

        if (databaseAlreadyExists && !allowExistingPlaintextDatabase) {
            throw DatabaseKeyUnavailableException(
                "The encrypted Galaxy database exists, but its wrapped key is missing."
            )
        }

        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            val wrapped = cipher.iv + cipher.doFinal(databaseKey)
            check(preferences.edit()
                .putString(WRAPPED_KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .commit()) { "Could not persist the wrapped Galaxy database key." }
            return databaseKey
        } catch (failure: Exception) {
            databaseKey.fill(0)
            throw DatabaseKeyUnavailableException("Could not create the Galaxy database key.", failure)
        }
    }

    private fun unwrapDatabaseKey(encoded: String): ByteArray {
        try {
            val wrapped = Base64.decode(encoded, Base64.NO_WRAP)
            require(wrapped.size > IV_BYTES + GCM_TAG_BYTES) { "Wrapped database key is truncated." }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getExistingWrappingKey(),
                GCMParameterSpec(TAG_BITS, wrapped.copyOfRange(0, IV_BYTES))
            )
            return cipher.doFinal(wrapped.copyOfRange(IV_BYTES, wrapped.size)).also {
                require(it.size == DATABASE_KEY_BYTES) { "Unexpected database key length." }
            }
        } catch (failure: Exception) {
            throw DatabaseKeyUnavailableException(
                "The saved Galaxy database key cannot be recovered. Existing data was preserved.",
                failure
            )
        }
    }

    private fun getExistingWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("The Galaxy database wrapping key is missing.")
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return JceKeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        const val PREFERENCES_NAME = "galaxy_database_key"
        private const val WRAPPED_KEY = "wrapped_sqlcipher_key_v1"
        private const val KEY_ALIAS = "galaxy_database_key_wrap_aes"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DATABASE_KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val TAG_BITS = 128
    }
}

class DatabaseKeyUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
