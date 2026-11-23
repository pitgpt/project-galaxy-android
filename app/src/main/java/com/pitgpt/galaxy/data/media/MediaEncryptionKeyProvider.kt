package com.pitgpt.galaxy.data.media

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The device-bound media key cannot currently be retrieved or used.
 * Callers must not create a replacement while ciphertext may still depend on this key.
 */
class MediaEncryptionKeyUnavailableException(cause: Throwable? = null) :
    IllegalStateException("The Galaxy media encryption key is unavailable; encrypted media was preserved.", cause)

/** Supplies a non-exportable AES key for local media encryption. */
interface MediaEncryptionKeyProvider {
    fun getOrCreateKey(): SecretKey
    fun getExistingKey(): SecretKey
}

/** Device-bound media key. Reading never creates a replacement for a missing key. */
class AndroidKeystoreMediaKeyProvider internal constructor(
    private val keyAlias: String = KEY_ALIAS
) : MediaEncryptionKeyProvider {
    @Synchronized
    override fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: error("The Galaxy media encryption key exists but is unavailable.")
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(KEY_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    @Synchronized
    override fun getExistingKey(): SecretKey {
        val key = try {
            loadKeyStore().getKey(keyAlias, null)
        } catch (failure: Exception) {
            throw MediaEncryptionKeyUnavailableException(failure)
        }
        return key as? SecretKey ?: throw MediaEncryptionKeyUnavailableException()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "galaxy_media_aes_gcm_v1"
        const val KEY_BITS = 256
    }
}
