package com.pitgpt.galaxy.data.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSettingsOperationsTest {
    private val first = AiProviderConfig("first", "First", "https://one.example", "key-one", "model-one")
    private val second = AiProviderConfig("second", "Second", "https://two.example", "key-two", "model-two")

    @Test
    fun removingActiveProviderSelectsFirstRemainingProvider() {
        val settings = ProviderSettings(
            providers = listOf(first, second),
            activeProviderId = "first"
        )

        val result = settings.withProviderRemoved("first")

        assertEquals(listOf(second), result.providers)
        assertEquals("second", result.activeProviderId)
    }

    @Test
    fun removingInactiveProviderPreservesActiveProvider() {
        val settings = ProviderSettings(
            providers = listOf(first, second),
            activeProviderId = "first"
        )

        val result = settings.withProviderRemoved("second")

        assertEquals(listOf(first), result.providers)
        assertEquals("first", result.activeProviderId)
    }

    @Test
    fun removingLastProviderClearsActiveSelection() {
        val settings = ProviderSettings(
            providers = listOf(first),
            activeProviderId = "first"
        )

        val result = settings.withProviderRemoved("first")

        assertEquals(emptyList<AiProviderConfig>(), result.providers)
        assertEquals(null, result.activeProviderId)
    }

    @Test
    fun removingAiProviderPreservesComfyUiConnectionSettings() {
        val comfyUi = ComfyUiSavedSettings(
            lanBaseUrl = "http://192.168.1.20:8188",
            lanBearerToken = "local-secret"
        )
        val settings = ProviderSettings(
            providers = listOf(first, second),
            activeProviderId = "first",
            comfyUi = comfyUi
        )

        val result = settings.withProviderRemoved("first")

        assertEquals(comfyUi, result.comfyUi)
    }

    @Test
    fun refusesReplacementKeyWhenEncryptedSettingsExistButKeystoreAliasIsMissing() {
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            requireProviderKeyCreationAllowed(hasStoredCiphertext = true, keyAliasExists = false)
        }
    }

    @Test
    fun allowsFirstRunKeyCreationWhenNoEncryptedSettingsExist() {
        requireProviderKeyCreationAllowed(hasStoredCiphertext = false, keyAliasExists = false)
    }

    @Test
    fun allowsExistingKeystoreKeyToUnlockExistingCiphertext() {
        requireProviderKeyCreationAllowed(hasStoredCiphertext = true, keyAliasExists = true)
    }
}
