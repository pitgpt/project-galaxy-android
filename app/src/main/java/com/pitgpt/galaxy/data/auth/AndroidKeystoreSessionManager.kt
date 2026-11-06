package com.pitgpt.galaxy.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the complete Supabase session as AES-GCM ciphertext.
 * The AES key is non-exportable and managed by Android Keystore.
 */
class AndroidKeystoreSessionManager(
    context: Context,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
) : SessionManager {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override suspend fun saveSession(session: UserSession) {
        val plaintext = json.encodeToString(session).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        val payload = cipher.iv + encrypted
        check(preferences.edit().putString(SESSION_KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()) {
            "Could not persist the encrypted authentication session"
        }
    }

    override suspend fun loadSession(): UserSession? {
        val encoded = preferences.getString(SESSION_KEY, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_LENGTH_BYTES) { "Invalid encrypted session payload" }
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            json.decodeFromString<UserSession>(plaintext)
        } catch (exception: Exception) {
            // A lost/invalidated Keystore key or corrupt ciphertext must never become an auth session.
            deleteSession()
            null
        }
    }

    override suspend fun deleteSession() {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "Could not delete the encrypted authentication session"
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "galaxy_secure_auth"
        const val SESSION_KEY = "supabase_session_v1"
        const val KEY_ALIAS = "galaxy_supabase_session_aes"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
