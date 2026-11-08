package com.pitgpt.galaxy.data.media

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Supplies a non-exportable AES key for local media encryption. */
interface MediaEncryptionKeyProvider {
    fun getOrCreateKey(): SecretKey
    fun getExistingKey(): SecretKey
}

/** Device-bound media key. Reading never creates a replacement for a missing key. */
class AndroidKeystoreMediaKeyProvider : MediaEncryptionKeyProvider {
    @Synchronized
    override fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("The Galaxy media encryption key exists but is unavailable.")
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
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
        val keyStore = loadKeyStore()
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("The Galaxy media encryption key is missing; encrypted media was preserved.")
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "galaxy_media_aes_gcm_v1"
        const val KEY_BITS = 256
    }
}
