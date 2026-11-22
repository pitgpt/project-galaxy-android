package com.pitgpt.galaxy.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the complete Supabase session as AES-GCM ciphertext.
 * The AES key is non-exportable and managed by Android Keystore.
 *
 * Plaintext serialization uses a fixed-size buffer, avoiding abandoned
 * plaintext arrays during growth. The active buffer is zeroized on every exit.
 * Immutable token Strings required by UserSession remain owned by Supabase.
 */
@OptIn(ExperimentalSerializationApi::class)
class AndroidKeystoreSessionManager(
    context: Context,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
    preferencesName: String = PREFS_NAME,
    private val sessionKey: String = SESSION_KEY,
    private val keyAlias: String = KEY_ALIAS
) : SessionManager {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE
    )

    override suspend fun saveSession(session: UserSession) {
        val plaintext = ZeroizingSessionOutputStream()
        try {
            json.encodeToStream(session, plaintext)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = plaintext.useBytes { bytes, length ->
                cipher.doFinal(bytes, 0, length)
            }
            try {
                val iv = cipher.iv
                try {
                    val payload = ByteArray(iv.size + encrypted.size)
                    try {
                        System.arraycopy(iv, 0, payload, 0, iv.size)
                        System.arraycopy(encrypted, 0, payload, iv.size, encrypted.size)
                        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
                        check(preferences.edit().putString(sessionKey, encoded).commit()) {
                            "Could not persist the encrypted authentication session"
                        }
                    } finally {
                        payload.fill(0)
                    }
                } finally {
                    iv.fill(0)
                }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            plaintext.wipe()
        }
    }

    override suspend fun loadSession(): UserSession? {
        val encoded = preferences.getString(sessionKey, null) ?: return null
        if (encoded.length > MAX_ENCODED_SESSION_CHARS) return null
        return try {
            // Loading must never create a replacement key: existing ciphertext can only be
            // decrypted with its original key, and a missing/invalidated key needs explicit recovery.
            val key = getExistingKey() ?: return null
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            try {
                require(payload.size > GCM_IV_LENGTH_BYTES) { "Invalid encrypted session payload" }
                val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
                try {
                    val ciphertext = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)
                    try {
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                        val plaintext = cipher.doFinal(ciphertext)
                        try {
                            ByteArrayInputStream(plaintext).use { input ->
                                json.decodeFromStream<UserSession>(input)
                            }
                        } finally {
                            plaintext.fill(0)
                        }
                    } finally {
                        ciphertext.fill(0)
                    }
                } finally {
                    iv.fill(0)
                }
            } finally {
                payload.fill(0)
            }
        } catch (_: Exception) {
            // Preserve ciphertext on key invalidation, authentication failure, or malformed data.
            null
        }
    }

    override suspend fun deleteSession() {
        check(preferences.edit().remove(sessionKey).commit()) {
            "Could not delete the encrypted authentication session"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
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

    private fun getExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private class ZeroizingSessionOutputStream : OutputStream() {
        private val buffer = ByteArray(MAX_SESSION_PLAINTEXT_BYTES)
        private var count = 0

        override fun write(value: Int) {
            ensureCapacity(1)
            buffer[count++] = value.toByte()
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > source.size - length) {
                throw IndexOutOfBoundsException("Invalid session serialization buffer range")
            }
            ensureCapacity(length)
            System.arraycopy(source, offset, buffer, count, length)
            count += length
        }

        fun <T> useBytes(block: (ByteArray, Int) -> T): T = block(buffer, count)

        fun wipe() {
            buffer.fill(0)
            count = 0
        }

        private fun ensureCapacity(additional: Int) {
            if (additional > buffer.size - count) {
                throw IOException("Supabase session exceeds the encrypted storage limit")
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "galaxy_secure_auth"
        const val SESSION_KEY = "supabase_session_v1"
        const val KEY_ALIAS = "galaxy_supabase_session_aes"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val MAX_SESSION_PLAINTEXT_BYTES = 64 * 1024
        const val MAX_ENCODED_SESSION_CHARS = 128 * 1024
    }
}
