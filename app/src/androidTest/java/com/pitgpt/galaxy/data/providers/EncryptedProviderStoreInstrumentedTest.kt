package com.pitgpt.galaxy.data.providers

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedProviderStoreInstrumentedTest {
    private lateinit var store: EncryptedProviderStore
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var keyAlias: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val preferencesName = "galaxy_provider_vault_test_$suffix"
        keyAlias = "galaxy_provider_vault_test_$suffix"
        store = EncryptedProviderStore(context, preferencesName, keyAlias)
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun workflowRoundTripsThroughNonExportableAndroidKeystoreKey() {
        val workflowJson = """{"1":{"inputs":{"text":"placeholder"}}}"""
        val workflow = ComfyUiWorkflowTemplate(
            id = "instrumented-workflow-v1",
            workflowJson = workflowJson,
            positivePrompt = ComfyUiNodeInput("1", "text")
        )

        store.save(ProviderSettings(comfyUiWorkflow = workflow))

        val ciphertext = preferences.getString("encrypted_provider_settings_v1", null)
        assertNotNull(ciphertext)
        assertFalse("Workflow JSON must not appear in plaintext preferences", ciphertext!!.contains(workflowJson))
        assertFalse("Workflow prompt placeholder must not appear in plaintext preferences", ciphertext.contains("placeholder"))
        assertEquals(workflow, store.load().comfyUiWorkflow)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(keyAlias, null) as SecretKey
        assertNull("Android Keystore must not export the vault key", key.encoded)
    }

    @Test
    fun invalidWorkflowMappingIsRejectedBeforeCiphertextIsWritten() {
        val workflow = ComfyUiWorkflowTemplate(
            id = "invalid-workflow-v1",
            workflowJson = """{"1":{"inputs":{"text":""}}}""",
            positivePrompt = ComfyUiNodeInput("1", "missing")
        )

        assertThrows(IllegalArgumentException::class.java) {
            store.save(ProviderSettings(comfyUiWorkflow = workflow))
        }

        assertFalse(preferences.contains("encrypted_provider_settings_v1"))
    }
}
