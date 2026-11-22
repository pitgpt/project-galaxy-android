package com.pitgpt.galaxy.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.auth.user.UserSession
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSessionManagerInstrumentedTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var manager: AndroidKeystoreSessionManager
    private lateinit var sessionKey: String
    private lateinit var keyAlias: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "galaxy_secure_auth_test_$suffix"
        sessionKey = "supabase_session_v1"
        keyAlias = "galaxy_supabase_session_test_$suffix"
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        manager = AndroidKeystoreSessionManager(
            context = context,
            preferencesName = preferencesName,
            sessionKey = sessionKey,
            keyAlias = keyAlias
        )
    }

    @After
    fun tearDown() {
        if (::preferences.isInitialized) preferences.edit().clear().commit()
        if (::keyAlias.isInitialized) {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    @Test
    fun saveAndLoadRoundTripsEncryptedSessionWithoutPersistingTokenText() {
        val session = UserSession(
            accessToken = "access-token-secret",
            refreshToken = "refresh-token-secret",
            expiresIn = 3600,
            tokenType = "bearer"
        )

        kotlinx.coroutines.runBlocking { manager.saveSession(session) }

        val encoded = preferences.getString(sessionKey, null)
        assertNotNull(encoded)
        assertFalse(encoded!!.contains(session.accessToken))
        assertFalse(encoded.contains(session.refreshToken))
        assertEquals(session, kotlinx.coroutines.runBlocking { manager.loadSession() })
    }

    @Test
    fun missingKeyPreservesStoredCiphertextWithoutCreatingReplacement() {
        val encoded = encodedCiphertext()
        assertTrue(preferences.edit().putString(sessionKey, encoded).commit())
        assertFalse(hasKey())

        assertNull(kotlinx.coroutines.runBlocking { manager.loadSession() })

        assertEquals(encoded, preferences.getString(sessionKey, null))
        assertFalse("Loading must not generate a replacement Android Keystore key", hasKey())
    }

    @Test
    fun authenticationFailurePreservesStoredCiphertext() {
        createKey()
        val encoded = encodedCiphertext()
        assertTrue(preferences.edit().putString(sessionKey, encoded).commit())

        assertNull(kotlinx.coroutines.runBlocking { manager.loadSession() })

        assertEquals(encoded, preferences.getString(sessionKey, null))
        assertTrue("A failed GCM authentication must not delete the existing key", hasKey())
    }

    @Test
    fun explicitDeleteSessionRemovesStoredCiphertext() {
        val encoded = encodedCiphertext()
        assertTrue(preferences.edit().putString(sessionKey, encoded).commit())

        kotlinx.coroutines.runBlocking { manager.deleteSession() }

        assertNull(preferences.getString(sessionKey, null))
    }

    private fun encodedCiphertext(): String {
        val payload = ByteArray(GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) { index ->
            (index + 1).toByte()
        }
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun createKey() {
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
        generator.generateKey()
    }

    private fun hasKey(): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.containsAlias(keyAlias)
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BYTES = 16
    }
}
