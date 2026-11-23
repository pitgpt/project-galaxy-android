package com.pitgpt.galaxy.data.auth

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
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
class EncryptedGalaxyBackendConfigStoreInstrumentedTest {
    private lateinit var preferencesName: String
    private lateinit var keyAlias: String
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var store: EncryptedGalaxyBackendConfigStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        preferencesName = "galaxy_backend_config_test_$suffix"
        keyAlias = "galaxy_backend_config_test_$suffix"
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        store = EncryptedGalaxyBackendConfigStore(
            context = context,
            preferencesName = preferencesName,
            keyAlias = keyAlias
        )
    }

    @After
    fun tearDown() {
        if (::store.isInitialized) runCatching { store.clear() }
    }

    @Test
    fun encryptedConfigurationRoundTripsWithoutPlaintextPreferences() {
        val configuration = validConfiguration()
        store.save(configuration)

        assertEquals(configuration.validated(), store.load())
        assertTrue(store.hasStoredConfiguration())
        val encoded = preferences.getString(
            EncryptedGalaxyBackendConfigStore.CONFIGURATION_KEY,
            null
        )
        assertNotNull(encoded)
        assertFalse(encoded.orEmpty().contains("api.example.test"))
        assertFalse(encoded.orEmpty().contains("sb_publishable_example"))
    }

    @Test
    fun missingKeystoreKeyPreservesCiphertextAndDoesNotCreateReplacement() {
        val encoded = Base64.encodeToString(ByteArray(64) { (it + 1).toByte() }, Base64.NO_WRAP)
        assertTrue(
            preferences.edit()
                .putString(EncryptedGalaxyBackendConfigStore.CONFIGURATION_KEY, encoded)
                .commit()
        )

        assertNull(store.load())

        assertEquals(
            encoded,
            preferences.getString(EncryptedGalaxyBackendConfigStore.CONFIGURATION_KEY, null)
        )
        assertFalse(hasKey())
    }

    @Test
    fun authenticationFailurePreservesExistingCiphertext() {
        store.save(validConfiguration())
        val encoded = requireNotNull(
            preferences.getString(EncryptedGalaxyBackendConfigStore.CONFIGURATION_KEY, null)
        )
        val corrupted = Base64.decode(encoded, Base64.NO_WRAP)
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        val corruptedEncoded = Base64.encodeToString(corrupted, Base64.NO_WRAP)
        corrupted.fill(0)
        assertTrue(
            preferences.edit()
                .putString(
                    EncryptedGalaxyBackendConfigStore.CONFIGURATION_KEY,
                    corruptedEncoded
                )
                .commit()
        )

        assertNull(store.load())

        assertEquals(
            corruptedEncoded,
            preferences.getString(EncryptedGalaxyBackendConfigStore.CONFIGURATION_KEY, null)
        )
        assertTrue(hasKey())
    }

    @Test
    fun explicitClearRemovesCiphertextAndDeviceBoundKey() {
        store.save(validConfiguration())
        assertTrue(hasKey())

        store.clear()

        assertFalse(store.hasStoredConfiguration())
        assertFalse(hasKey())
    }

    private fun validConfiguration() = GalaxyBackendConfiguration(
        galaxyApiBaseUrl = "https://api.example.test",
        supabaseUrl = "https://project.supabase.co",
        supabasePublishableKey = "sb_publishable_example"
    )

    private fun hasKey(): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.containsAlias(keyAlias)
    }
}
