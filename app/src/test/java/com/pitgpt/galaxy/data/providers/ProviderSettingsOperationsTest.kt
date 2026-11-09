package com.pitgpt.galaxy.data.providers

import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun removingAiProviderPreservesComfyUiWorkflowTemplate() {
        val workflow = ComfyUiWorkflowTemplate(
            id = "test-workflow-v1",
            workflowJson = """{"1":{"inputs":{"text":""}}}""",
            positivePrompt = ComfyUiNodeInput("1", "text")
        )
        val settings = ProviderSettings(
            providers = listOf(first),
            activeProviderId = "first",
            comfyUiWorkflow = workflow
        )

        val result = settings.withProviderRemoved("first")

        assertEquals(workflow, result.comfyUiWorkflow)
    }

    @Test
    fun providerSettingsStringRedactsApiKeysAndImportedWorkflowContent() {
        val secretApiKey = "provider-api-key-do-not-log"
        val privateWorkflowText = "private-workflow-prompt-do-not-log"
        val provider = first.copy(apiKey = secretApiKey)
        val workflow = ComfyUiWorkflowTemplate(
            id = "private-workflow",
            workflowJson = """{"1":{"inputs":{"text":"$privateWorkflowText"}}}""",
            positivePrompt = ComfyUiNodeInput("1", "text")
        )
        val settings = ProviderSettings(
            providers = listOf(provider),
            comfyUiWorkflow = workflow
        )

        assertFalse(provider.toString().contains(secretApiKey))
        assertTrue(provider.toString().contains("apiKeyConfigured=true"))
        assertFalse(workflow.toString().contains(privateWorkflowText))
        assertFalse(settings.toString().contains(secretApiKey))
        assertFalse(settings.toString().contains(privateWorkflowText))
    }

    @Test
    fun nestedSettingsStringRepresentationsDoNotExposeUserControlledValues() {
        val apiKey = "api-key-do-not-log"
        val urlToken = "url-token-do-not-log"
        val baseUrl = "https://provider.example/v1?token=$urlToken"
        val providerName = "private-provider-name"
        val modelName = "private-model-name"
        val providerId = "private-provider-id"
        val textModel = "private-text-model"
        val voiceModel = "private-voice-model"
        val workflowId = "private-workflow-id"
        val workflowText = "private-workflow-prompt"
        val lanToken = "lan-bearer-token-do-not-log"
        val vpnToken = "vpn-bearer-token-do-not-log"

        val provider = first.copy(
            id = providerId,
            name = providerName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = modelName
        )
        val workflow = ComfyUiWorkflowTemplate(
            id = workflowId,
            workflowJson = """{"1":{"inputs":{"text":"$workflowText"}}}""",
            positivePrompt = ComfyUiNodeInput("1", "text")
        )
        val comfyUi = ComfyUiSavedSettings(
            lanBaseUrl = "https://lan.example",
            lanBearerToken = lanToken,
            vpnBaseUrl = "https://vpn.example",
            vpnBearerToken = vpnToken
        )
        val settings = ProviderSettings(
            providers = listOf(provider),
            activeProviderId = providerId,
            textModel = textModel,
            voiceModel = voiceModel,
            comfyUi = comfyUi,
            comfyUiWorkflow = workflow
        )

        val protectedValues = listOf(
            apiKey, urlToken, baseUrl, providerName, modelName, providerId,
            textModel, voiceModel, workflowId, workflowText, lanToken, vpnToken
        )
        val representations = listOf(
            provider.toString(),
            workflow.toString(),
            comfyUi.toString(),
            settings.toString()
        )
        representations.forEach { representation ->
            protectedValues.forEach { value ->
                assertFalse("Sensitive value leaked by toString", representation.contains(value))
            }
        }
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
