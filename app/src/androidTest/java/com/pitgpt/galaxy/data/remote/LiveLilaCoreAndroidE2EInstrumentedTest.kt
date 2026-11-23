package com.pitgpt.galaxy.data.remote

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.MessageRole
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs only in the dedicated Android CI job. The test CA is installed into the
 * ephemeral emulator's system trust store; the application keeps HTTPS-only
 * transport and its production network-security policy.
 */
@RunWith(AndroidJUnit4::class)
class LiveLilaCoreAndroidE2EInstrumentedTest {
    @Test
    fun authenticatedAndroidClientSwitchesProvidersAndCommitsSqlCipherHistory() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "The dedicated live Android/Core job did not enable this test",
            arguments.getString("lilaLiveE2e") == "true"
        )

        val config = readEphemeralConfig()
        val baseUrl = requiredString(config, "base_url")
        val accessToken = requiredString(config, "access_token")
        val ownerKey = requiredString(config, "owner_key")
        val characterId = requiredString(config, "character_id")
        val providerId = requiredString(config, "provider_id")
        val providerBaseUrl = requiredString(config, "provider_base_url")
        val modelId = requiredString(config, "model_id")
        val conversationId = stableLilaConversationId(ownerKey, characterId)
        val storageCharacterId = "owner:$ownerKey:$characterId"
        val tokenProvider = GalaxyAccessTokenProvider { accessToken }
        val catalogRequestIds = mutableListOf<String>()
        val catalogDiagnostics = mutableListOf<LilaRequestDiagnostic>()
        val catalog = HttpLilaProviderCatalog(
            baseUrl = baseUrl,
            accessTokenProvider = tokenProvider,
            requestIdProvider = {
                UUID.randomUUID().toString().also(catalogRequestIds::add)
            },
            diagnosticObserver = catalogDiagnostics::add
        )
        suspend fun <T> catalogStep(name: String, action: suspend () -> T): T {
            try {
                return action()
            } catch (exception: Exception) {
                val diagnostic = catalogDiagnostics.lastOrNull()
                val requestId = diagnostic?.requestId
                    ?: catalogRequestIds.lastOrNull()
                    ?: "not-issued"
                val operation = diagnostic?.operation ?: name
                val category = diagnostic?.category ?: "not-captured"
                val httpStatus = diagnostic?.httpStatus?.toString() ?: "none"
                val failure = AssertionError(
                    "Live Lila catalog step=${operation} request_id=${requestId} " +
                        "category=${category} http_status=${httpStatus} " +
                        "exception=${exception.javaClass.simpleName}"
                )
                failure.initCause(exception)
                throw failure
            }
        }
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as GalaxyApplication
        val openAiProviderId = "$providerId-openai"

        try {
            val created = catalogStep("create-provider") {
                catalog.createProvider(
                    providerId = providerId,
                    providerType = "local",
                    baseUrl = providerBaseUrl,
                    modelId = modelId,
                    apiKey = null,
                    timeoutSeconds = 120.0
                )
            }
            assertEquals(providerId, created.providerId)
            assertEquals("local", created.providerType)
            assertTrue(!created.hasApiKey)

            val probe = catalogStep("probe-provider") {
                catalog.probeProvider(providerId)
            }
            assertTrue(probe.connectionReachable)
            assertEquals(true, probe.configuredModelAvailable)
            assertTrue(probe.modelIds.contains(modelId))

            val selection = catalogStep("select-conversation-model") {
                catalog.setConversationModel(
                    conversationId = conversationId,
                    providerId = providerId,
                    modelId = modelId
                )
            }
            assertTrue(selection.selected)
            assertEquals(providerId, selection.providerId)
            assertEquals(modelId, selection.modelId)

            val turnProvider = HttpLilaTurnProvider(baseUrl, tokenProvider)
            val messageText = "Привет, Лила. Ответь одним коротким предложением."
            val idempotencyKey = UUID.randomUUID().toString()
            val createdAt = System.currentTimeMillis()
            val pendingId = "lila-pending-$idempotencyKey"
            val pending = ConversationMessage(
                id = pendingId,
                characterId = storageCharacterId,
                role = MessageRole.USER,
                content = messageText,
                createdAtEpochMillis = createdAt
            )

            // This is the same durable outbox boundary used by GalaxyHome:
            // persist the pending user turn before the authenticated HTTP call.
            app.conversations.append(pending)
            val result = turnProvider.sendTurn(
                conversationId = conversationId,
                message = messageText,
                idempotencyKey = idempotencyKey
            )

            assertEquals(conversationId, result.conversationId)
            assertEquals(providerId, result.providerId)
            assertEquals(modelId, result.modelId)
            assertTrue(result.text.isNotBlank())

            val remoteHistory = turnProvider.loadAllHistory(conversationId)
            assertEquals(2, remoteHistory.size)
            assertEquals("user", remoteHistory[0].role)
            assertEquals(messageText, remoteHistory[0].content)
            assertEquals("assistant", remoteHistory[1].role)
            assertEquals(result.text, remoteHistory[1].content)

            val userMessage = ConversationMessage(
                id = "lila-$conversationId-user-$idempotencyKey",
                characterId = storageCharacterId,
                role = MessageRole.USER,
                content = messageText,
                createdAtEpochMillis = createdAt
            )
            val assistantMessage = ConversationMessage(
                id = "lila-$conversationId-assistant-$idempotencyKey",
                characterId = storageCharacterId,
                role = MessageRole.CHARACTER,
                content = result.text,
                createdAtEpochMillis = System.currentTimeMillis()
            )
            app.conversations.commitPendingTurn(
                pendingMessageId = pendingId,
                userMessage = userMessage,
                assistantMessage = assistantMessage
            )

            val localHistory = app.conversations.getForCharacter(storageCharacterId)
            assertEquals(2, localHistory.size)
            assertEquals(listOf(MessageRole.USER, MessageRole.CHARACTER), localHistory.map { it.role })
            assertEquals(messageText, localHistory[0].content)
            assertEquals(result.text, localHistory[1].content)

            // Exercise the same local Ollama runtime through its OpenAI-compatible /v1 API.
            // The resolver must select OpenAICompatibleModelAdapter for this path, not native /api/chat.
            val openAiProvider = catalogStep("create-openai-compatible-provider") {
                catalog.createProvider(
                    providerId = openAiProviderId,
                    providerType = "local",
                    baseUrl = providerBaseUrl.trimEnd('/') + "/v1",
                    modelId = modelId,
                    apiKey = null,
                    timeoutSeconds = 120.0
                )
            }
            assertEquals(openAiProviderId, openAiProvider.providerId)
            assertEquals("local", openAiProvider.providerType)
            assertTrue(!openAiProvider.hasApiKey)

            val openAiProbe = catalogStep("probe-openai-compatible-provider") {
                catalog.probeProvider(openAiProviderId)
            }
            assertTrue(openAiProbe.connectionReachable)
            assertEquals(true, openAiProbe.configuredModelAvailable)
            assertTrue(openAiProbe.modelIds.contains(modelId))

            val switchedSelection = catalogStep("switch-conversation-model") {
                catalog.setConversationModel(
                    conversationId = conversationId,
                    providerId = openAiProviderId,
                    modelId = modelId
                )
            }
            assertTrue(switchedSelection.selected)
            assertEquals(openAiProviderId, switchedSelection.providerId)
            assertEquals(modelId, switchedSelection.modelId)

            val secondMessage = "Продолжи тот же разговор и кратко подтверди переключение модели."
            val secondIdempotencyKey = UUID.randomUUID().toString()
            val secondCreatedAt = System.currentTimeMillis()
            val secondPendingId = "lila-pending-$secondIdempotencyKey"
            app.conversations.append(
                ConversationMessage(
                    id = secondPendingId,
                    characterId = storageCharacterId,
                    role = MessageRole.USER,
                    content = secondMessage,
                    createdAtEpochMillis = secondCreatedAt
                )
            )
            val secondResult = turnProvider.sendTurn(
                conversationId = conversationId,
                message = secondMessage,
                idempotencyKey = secondIdempotencyKey
            )
            assertEquals(conversationId, secondResult.conversationId)
            assertEquals(openAiProviderId, secondResult.providerId)
            assertEquals(modelId, secondResult.modelId)
            assertTrue(secondResult.text.isNotBlank())

            val switchedRemoteHistory = turnProvider.loadAllHistory(conversationId)
            assertEquals(4, switchedRemoteHistory.size)
            assertEquals(listOf("user", "assistant", "user", "assistant"), switchedRemoteHistory.map { it.role })
            assertEquals(messageText, switchedRemoteHistory[0].content)
            assertEquals(result.text, switchedRemoteHistory[1].content)
            assertEquals(providerId, switchedRemoteHistory[1].providerId)
            assertEquals(modelId, switchedRemoteHistory[1].modelId)
            assertEquals(secondMessage, switchedRemoteHistory[2].content)
            assertEquals(secondResult.text, switchedRemoteHistory[3].content)
            assertEquals(openAiProviderId, switchedRemoteHistory[3].providerId)
            assertEquals(modelId, switchedRemoteHistory[3].modelId)

            app.conversations.commitPendingTurn(
                pendingMessageId = secondPendingId,
                userMessage = ConversationMessage(
                    id = "lila-$conversationId-user-$secondIdempotencyKey",
                    characterId = storageCharacterId,
                    role = MessageRole.USER,
                    content = secondMessage,
                    createdAtEpochMillis = secondCreatedAt
                ),
                assistantMessage = ConversationMessage(
                    id = "lila-$conversationId-assistant-$secondIdempotencyKey",
                    characterId = storageCharacterId,
                    role = MessageRole.CHARACTER,
                    content = secondResult.text,
                    createdAtEpochMillis = System.currentTimeMillis()
                )
            )
            val switchedLocalHistory = app.conversations.getForCharacter(storageCharacterId)
            assertEquals(4, switchedLocalHistory.size)
            assertEquals(secondMessage, switchedLocalHistory[2].content)
            assertEquals(secondResult.text, switchedLocalHistory[3].content)
            val restoredSelection = catalogStep("restore-conversation-model") {
                catalog.getConversationModel(conversationId)
            }
            assertTrue(restoredSelection.selected)
            assertEquals(openAiProviderId, restoredSelection.providerId)
            assertEquals(modelId, restoredSelection.modelId)

            val integrity = app.database.openHelper.writableDatabase
                .query("PRAGMA integrity_check").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
            assertEquals("ok", integrity)
        } finally {
            // The owner and provider are unique to this CI invocation. Remove only
            // this test's local conversation and provider; the backend DB is ephemeral.
            runCatching { app.conversations.clearForCharacter(storageCharacterId) }
            runCatching { catalog.deleteProvider(openAiProviderId) }
            runCatching { catalog.deleteProvider(providerId) }
        }
    }

    private fun readEphemeralConfig(): JSONObject {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat /data/local/tmp/lila-android-e2e.json")
        val content = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        assertTrue(content.isNotBlank())
        return JSONObject(content)
    }

    private fun requiredString(config: JSONObject, name: String): String {
        val value = config.optString(name, "")
        assertNotNull("$name is missing from the ephemeral E2E configuration", value.takeIf { it.isNotBlank() })
        return value
    }
}
