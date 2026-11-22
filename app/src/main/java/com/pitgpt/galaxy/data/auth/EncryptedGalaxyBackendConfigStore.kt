package com.pitgpt.galaxy.data.auth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Stores Galaxy/Supabase connection configuration as AES-GCM ciphertext.
 * The wrapping key is non-exportable and owned by Android Keystore.
 *
 * A missing or invalidated key never causes load() to generate a replacement:
 * existing ciphertext is retained until the user explicitly saves a new
 * configuration or invokes clear() from the recovery screen.
 */
class EncryptedGalaxyBackendConfigStore(
    context: Context,
    preferencesName: String = PREFS_NAME,
    private val keyAlias: String = KEY_ALIAS
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    fun hasStoredConfiguration(): Boolean = preferences.contains(CONFIGURATION_KEY)

    fun load(): GalaxyBackendConfiguration? {
        val encoded = preferences.getString(CONFIGURATION_KEY, null) ?: return null
        return try {
            val key = getExistingKey() ?: return null
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            try {
                require(payload.size > GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES)
                val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
                val encrypted = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)
                try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        key,
                        GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                    )
                    cipher.updateAAD(ASSOCIATED_DATA)
                    val plaintext = cipher.doFinal(encrypted)
                    try {
                        val json = JSONObject(plaintext.toString(Charsets.UTF_8))
                        GalaxyBackendConfiguration(
                            galaxyApiBaseUrl = json.getString("galaxy_api_base_url"),
                            supabaseUrl = json.getString("supabase_url"),
                            supabasePublishableKey = json.getString("supabase_publishable_key")
                        ).validated()
                    } finally {
                        plaintext.fill(0)
                    }
                } finally {
                    iv.fill(0)
                    encrypted.fill(0)
                }
            } finally {
                payload.fill(0)
            }
        } catch (_: Exception) {
            // Keep ciphertext intact on missing/invalidated keys, GCM failure or malformed data.
            null
        }
    }

    fun save(configuration: GalaxyBackendConfiguration) {
        val validated = configuration.validated()
        val plaintext = JSONObject()
            .put("galaxy_api_base_url", validated.galaxyApiBaseUrl)
            .put("supabase_url", validated.supabaseUrl)
            .put("supabase_publishable_key", validated.supabasePublishableKey)
            .toString()
            .toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(ASSOCIATED_DATA)
            val encrypted = cipher.doFinal(plaintext)
            val iv = cipher.iv
            val payload = ByteArray(iv.size + encrypted.size)
            try {
                System.arraycopy(iv, 0, payload, 0, iv.size)
                System.arraycopy(encrypted, 0, payload, iv.size, encrypted.size)
                val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
                check(
                    preferences.edit()
                        .putString(CONFIGURATION_KEY, encoded)
                        .commit()
                ) {
                    "Could not persist encrypted Galaxy backend configuration."
                }
            } finally {
                iv.fill(0)
                encrypted.fill(0)
                payload.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Explicit recovery action. This removes the encrypted configuration and
     * its device-bound key; it is never called implicitly by load().
     */
    fun clear() {
        check(preferences.edit().remove(CONFIGURATION_KEY).commit()) {
            "Could not clear encrypted Galaxy backend configuration."
        }
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    private fun getExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) return null
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw IllegalStateException("The existing Galaxy configuration key is unavailable.")
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    internal companion object {
        const val CONFIGURATION_KEY = "encrypted_configuration_v1"
        private const val PREFS_NAME = "galaxy_backend_config"
        private const val KEY_ALIAS = "galaxy_backend_config_aes"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BYTES = 16
        private const val GCM_TAG_LENGTH_BITS = 128
        private val ASSOCIATED_DATA = "galaxy-backend-config-v1".toByteArray(Charsets.UTF_8)
    }
}
