package com.pitgpt.galaxy.data.remote

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.data.local.EncryptedDatabaseKeyStore
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import com.pitgpt.galaxy.data.local.GalaxyDatabaseFactory
import com.pitgpt.galaxy.data.local.ZeroizingSqlCipherOpenHelperFactory
import com.pitgpt.galaxy.data.media.EncryptedMediaStore
import com.pitgpt.galaxy.data.providers.comfyui.RoomComfyUiJobStore
import com.pitgpt.galaxy.data.repository.RoomGeneratedImageRepository
import com.pitgpt.galaxy.data.repository.RoomImageGenerationRequestStore
import com.pitgpt.galaxy.core.model.ConversationMessage
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.model.MessageRole
import com.pitgpt.galaxy.core.usecase.PersistGeneratedImageResult
import com.pitgpt.galaxy.core.usecase.SubmitImageGeneration
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import com.pitgpt.galaxy.ui.lilaFallbackNoticeText
import com.pitgpt.galaxy.ui.mergeRemoteHistory
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
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
        val secondProviderBaseUrl = requiredString(config, "second_provider_base_url")
        val modelId = requiredString(config, "model_id")
        val llamaCppModelId = requiredString(config, "second_model_id")
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
        val llamaCppProviderId = requiredString(config, "llama_cpp_provider_id")
        var generatedImageRequestId: String? = null
        var generatedImageJobId: String? = null
        var generatedImageAssetId: String? = null

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

            // Switch to a real llama.cpp server through its OpenAI-compatible /v1 API.
            // The resolver must reach llama.cpp on its own port, not reuse Ollama's
            // native /api/chat transport or the primary provider configuration.
            val llamaCppProvider = catalogStep("create-llama-cpp-provider") {
                catalog.createProvider(
                    providerId = llamaCppProviderId,
                    providerType = "local",
                    baseUrl = secondProviderBaseUrl,
                    modelId = llamaCppModelId,
                    apiKey = null,
                    timeoutSeconds = 120.0
                )
            }
            assertEquals(llamaCppProviderId, llamaCppProvider.providerId)
            assertEquals("local", llamaCppProvider.providerType)
            assertTrue(!llamaCppProvider.hasApiKey)

            val llamaCppProbe = catalogStep("probe-llama-cpp-provider") {
                catalog.probeProvider(llamaCppProviderId)
            }
            assertTrue(llamaCppProbe.connectionReachable)
            assertEquals(true, llamaCppProbe.configuredModelAvailable)
            assertTrue(llamaCppProbe.modelIds.contains(llamaCppModelId))

            val switchedSelection = catalogStep("switch-conversation-model") {
                catalog.setConversationModel(
                    conversationId = conversationId,
                    providerId = llamaCppProviderId,
                    modelId = llamaCppModelId
                )
            }
            assertTrue(switchedSelection.selected)
            assertEquals(llamaCppProviderId, switchedSelection.providerId)
            assertEquals(llamaCppModelId, switchedSelection.modelId)

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
            assertEquals(llamaCppProviderId, secondResult.providerId)
            assertEquals(llamaCppModelId, secondResult.modelId)
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
            assertEquals(llamaCppProviderId, switchedRemoteHistory[3].providerId)
            assertEquals(llamaCppModelId, switchedRemoteHistory[3].modelId)

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
            assertEquals(llamaCppProviderId, restoredSelection.providerId)
            assertEquals(llamaCppModelId, restoredSelection.modelId)

            // Configure an explicit fallback while both independently running
            // providers are enabled and their models have already been discovered.
            val fallbackSelection = catalogStep("select-explicit-fallback") {
                catalog.setConversationModel(
                    conversationId = conversationId,
                    providerId = providerId,
                    modelId = modelId,
                    fallbackProviderId = llamaCppProviderId,
                    fallbackModelId = llamaCppModelId
                )
            }
            assertTrue(fallbackSelection.selected)
            assertEquals(providerId, fallbackSelection.providerId)
            assertEquals(llamaCppProviderId, fallbackSelection.fallbackProviderId)
            assertEquals(llamaCppModelId, fallbackSelection.fallbackModelId)

            // Disable only the test-owned primary provider after the selection has
            // been validated. Core must use the explicitly selected fallback and
            // must not silently choose any third provider.
            val disabledPrimary = catalogStep("disable-primary-provider") {
                catalog.updateProvider(
                    providerId = providerId,
                    providerType = "local",
                    baseUrl = providerBaseUrl,
                    modelId = modelId,
                    apiKey = null,
                    clearApiKey = false,
                    timeoutSeconds = 120.0,
                    enabled = false
                )
            }
            assertTrue(!disabledPrimary.enabled)

            val fallbackMessage = "Продолжим тот же разговор через явно выбранную резервную модель."
            val fallbackIdempotencyKey = UUID.randomUUID().toString()
            val fallbackCreatedAt = System.currentTimeMillis()
            val fallbackPendingId = "lila-pending-$fallbackIdempotencyKey"
            app.conversations.append(
                ConversationMessage(
                    id = fallbackPendingId,
                    characterId = storageCharacterId,
                    role = MessageRole.USER,
                    content = fallbackMessage,
                    createdAtEpochMillis = fallbackCreatedAt
                )
            )
            val fallbackResult = turnProvider.sendTurn(
                conversationId = conversationId,
                message = fallbackMessage,
                idempotencyKey = fallbackIdempotencyKey
            )
            assertEquals(conversationId, fallbackResult.conversationId)
            assertEquals(llamaCppProviderId, fallbackResult.providerId)
            assertEquals(llamaCppModelId, fallbackResult.modelId)
            assertTrue(fallbackResult.fallbackUsed)
            assertEquals(providerId, fallbackResult.fallbackFromProviderId)
            assertEquals(modelId, fallbackResult.fallbackFromModelId)
            assertEquals("primary_provider_unavailable", fallbackResult.fallbackReason)
            assertTrue(fallbackResult.text.isNotBlank())

            val fallbackRemoteHistory = turnProvider.loadAllHistory(conversationId)
            assertEquals(6, fallbackRemoteHistory.size)
            assertEquals(
                listOf("user", "assistant", "user", "assistant", "user", "assistant"),
                fallbackRemoteHistory.map { it.role }
            )
            val fallbackHistoryAssistant = fallbackRemoteHistory.last()
            assertEquals(fallbackMessage, fallbackRemoteHistory[4].content)
            assertEquals(fallbackResult.text, fallbackHistoryAssistant.content)
            assertEquals(llamaCppProviderId, fallbackHistoryAssistant.providerId)
            assertEquals(llamaCppModelId, fallbackHistoryAssistant.modelId)
            assertTrue(fallbackHistoryAssistant.fallbackUsed)
            assertEquals(providerId, fallbackHistoryAssistant.fallbackFromProviderId)
            assertEquals(modelId, fallbackHistoryAssistant.fallbackFromModelId)
            assertEquals("primary_provider_unavailable", fallbackHistoryAssistant.fallbackReason)

            app.conversations.commitPendingTurn(
                pendingMessageId = fallbackPendingId,
                userMessage = ConversationMessage(
                    id = "lila-$conversationId-user-$fallbackIdempotencyKey",
                    characterId = storageCharacterId,
                    role = MessageRole.USER,
                    content = fallbackMessage,
                    createdAtEpochMillis = fallbackCreatedAt
                ),
                assistantMessage = ConversationMessage(
                    id = "lila-$conversationId-assistant-$fallbackIdempotencyKey",
                    characterId = storageCharacterId,
                    role = MessageRole.CHARACTER,
                    content = fallbackResult.text,
                    createdAtEpochMillis = fallbackCreatedAt + 1
                )
            )

            val noticeText = lilaFallbackNoticeText(
                fallbackUsed = fallbackResult.fallbackUsed,
                providerId = fallbackResult.providerId,
                modelId = fallbackResult.modelId,
                fallbackFromProviderId = fallbackResult.fallbackFromProviderId,
                fallbackFromModelId = fallbackResult.fallbackFromModelId,
                fallbackReason = fallbackResult.fallbackReason
            )
            assertNotNull(noticeText)
            assertTrue(noticeText!!.contains("основной провайдер был недоступен до отправки"))
            assertTrue(noticeText.contains("$llamaCppProviderId · $llamaCppModelId"))

            // Exercise the same remote-history merge used when Galaxy reopens a
            // conversation after process restart. The durable Core disclosure must
            // recreate exactly one visible system notice without local cache state.
            val restoredAfterRestart = mergeRemoteHistory(
                characterId = storageCharacterId,
                conversationId = conversationId,
                local = emptyList(),
                remote = fallbackRemoteHistory
            )
            assertEquals(7, restoredAfterRestart.size)
            assertEquals(
                listOf(
                    MessageRole.USER,
                    MessageRole.CHARACTER,
                    MessageRole.USER,
                    MessageRole.CHARACTER,
                    MessageRole.USER,
                    MessageRole.CHARACTER,
                    MessageRole.SYSTEM
                ),
                restoredAfterRestart.map { it.role }
            )
            assertEquals(noticeText, restoredAfterRestart.last().content)
            app.conversations.replaceForCharacter(storageCharacterId, restoredAfterRestart)
            val persistedRestoredHistory = app.conversations.getForCharacter(storageCharacterId)
            assertEquals(restoredAfterRestart, persistedRestoredHistory)

            val fallbackReplay = turnProvider.sendTurn(
                conversationId = conversationId,
                message = fallbackMessage,
                idempotencyKey = fallbackIdempotencyKey
            )
            assertEquals(fallbackResult, fallbackReplay)
            assertEquals(6, turnProvider.loadAllHistory(conversationId).size)

            val restoredFallbackSelection = catalogStep("restore-fallback-selection") {
                catalog.getConversationModel(conversationId)
            }
            assertTrue(restoredFallbackSelection.selected)
            assertEquals(providerId, restoredFallbackSelection.providerId)
            assertEquals(modelId, restoredFallbackSelection.modelId)
            assertEquals(llamaCppProviderId, restoredFallbackSelection.fallbackProviderId)
            assertEquals(llamaCppModelId, restoredFallbackSelection.fallbackModelId)

            // Generate an image through the same authenticated Core and conversation used
            // by the real Ollama/llama.cpp chat above. ComfyUI runs as a pinned CPU API
            // server in this job; this verifies the complete transport/storage path without
            // claiming SDXL checkpoint inference.
            val imageProvider = LilaCoreImageGenerationProvider(catalog, app.comfyUiJobStore)
            val imageWorkflowId = "lila.android-live-empty-image.v1"
            val imageWorkflowJson = JSONObject()
                .put(
                    "1",
                    JSONObject()
                        .put("class_type", "EmptyImage")
                        .put(
                            "inputs",
                            JSONObject()
                                .put("width", 64)
                                .put("height", 64)
                                .put("batch_size", 1)
                                .put("color", 3368652)
                        )
                )
                .put(
                    "2",
                    JSONObject()
                        .put("class_type", "SaveImage")
                        .put(
                            "inputs",
                            JSONObject()
                                .put("images", org.json.JSONArray().put("1").put(0))
                                .put("filename_prefix", "lila-android-live")
                        )
                )
                .toString()
            val imageWorkflow = ComfyUiWorkflowTemplate(
                id = imageWorkflowId,
                workflowJson = imageWorkflowJson,
                positivePrompt = ComfyUiNodeInput("2", "filename_prefix"),
                width = ComfyUiNodeInput("1", "width"),
                height = ComfyUiNodeInput("1", "height")
            ).also { it.validateMappings() }
            val imageConfiguration = imageProvider.configureProvider(
                baseUrl = requiredString(config, "comfyui_base_url"),
                workflow = imageWorkflow,
                timeoutSeconds = 10.0
            )
            assertTrue(imageConfiguration.configured)
            assertEquals(imageWorkflowId, imageConfiguration.workflowId)
            val imageProbe = imageProvider.testProvider()
            assertTrue(imageProbe.connectionReachable)
            assertEquals(imageWorkflowId, imageProbe.workflowId)

            val imageRequest = ImageGenerationRequest(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                characterId = storageCharacterId,
                prompt = "lila-android-live-${UUID.randomUUID()}",
                preferredMimeType = "image/png",
                workflowId = imageWorkflowId,
                width = 64,
                height = 64,
                createdAtEpochMillis = System.currentTimeMillis()
            )
            generatedImageRequestId = imageRequest.id
            app.imageGenerationRequests.put(imageRequest)
            val imageJobId = SubmitImageGeneration(imageProvider)(imageRequest)
            generatedImageJobId = imageJobId

            var imageStatus = imageProvider.status(imageJobId)
            val imageDeadline = System.currentTimeMillis() + 120_000L
            while (
                imageStatus.state != ImageGenerationState.COMPLETED &&
                imageStatus.state != ImageGenerationState.FAILED &&
                imageStatus.state != ImageGenerationState.CANCELLED &&
                System.currentTimeMillis() < imageDeadline
            ) {
                delay(500)
                imageStatus = imageProvider.status(imageJobId)
            }
            assertEquals("ComfyUI image job did not complete", ImageGenerationState.COMPLETED, imageStatus.state)

            val imageHistoryJob = imageProvider.history(conversationId)
                .singleOrNull { it.jobId == imageJobId }
            assertNotNull("The image job must be visible in the same Core conversation", imageHistoryJob)
            assertEquals(conversationId, imageHistoryJob!!.conversationId)
            assertEquals(imageRequest.prompt, imageHistoryJob.prompt)
            assertEquals("completed", imageHistoryJob.state)

            val storedImage = PersistGeneratedImageResult(imageProvider, app.generatedImages)(
                imageRequest,
                imageJobId
            )
            generatedImageAssetId = storedImage.assetId
            assertEquals(imageRequest.id, storedImage.requestId)
            assertEquals(conversationId, storedImage.conversationId)
            assertEquals(storageCharacterId, storedImage.characterId)
            assertEquals(
                1,
                app.generatedImages.listForConversation(conversationId)
                    .count { it.requestId == imageRequest.id }
            )
            assertEquals(imageRequest, app.imageGenerationRequests.get(imageRequest.id))
            val localJob = app.comfyUiJobStore.get(imageJobId)
            assertNotNull(localJob)
            assertEquals(imageRequest.id, localJob!!.requestId)

            val persistedBytes = app.generatedImages.read(storedImage.assetId)
            try {
                val pngSignature = byteArrayOf(
                    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                )
                assertTrue(persistedBytes.size >= 24)
                assertTrue(persistedBytes.copyOfRange(0, 8).contentEquals(pngSignature))
                fun pngInt(offset: Int): Int =
                    ((persistedBytes[offset].toInt() and 0xff) shl 24) or
                        ((persistedBytes[offset + 1].toInt() and 0xff) shl 16) or
                        ((persistedBytes[offset + 2].toInt() and 0xff) shl 8) or
                        (persistedBytes[offset + 3].toInt() and 0xff)
                assertEquals(64, pngInt(16))
                assertEquals(64, pngInt(20))
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(persistedBytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                assertEquals(storedImage.sha256, digest)
                assertEquals(storedImage.byteSize, persistedBytes.size.toLong())
            } finally {
                persistedBytes.fill(0)
            }

            // The text turns and image job share the same stable conversation ID.
            assertEquals(6, turnProvider.loadAllHistory(conversationId).size)

            val integrity = app.database.openHelper.writableDatabase
                .query("PRAGMA integrity_check").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
            assertEquals("ok", integrity)

            val journalMode = app.database.openHelper.writableDatabase
                .query("PRAGMA journal_mode").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0).lowercase()
                }
            assertEquals("The production SQLCipher Room database must use WAL", "wal", journalMode)

            // Open a second Room/SQLCipher instance against the same encrypted file.
            // This forces a fresh Android Keystore unwrap and verifies that the image
            // metadata, request/job correlation, and AES-GCM bytes survive a database reopen.
            val reopenedDatabase = openIndependentEncryptedDatabase(app)
            try {
                reopenedDatabase.openHelper.writableDatabase
                val reopenedImages = RoomGeneratedImageRepository(
                    reopenedDatabase.generatedImageDao(),
                    EncryptedMediaStore.fromContext(app)
                )
                assertEquals(storedImage, reopenedImages.get(storedImage.assetId))
                assertEquals(
                    listOf(storedImage),
                    reopenedImages.listForConversation(conversationId)
                )
                assertEquals(storedImage, reopenedImages.getForRequest(imageRequest.id))
                assertEquals(
                    imageRequest,
                    RoomImageGenerationRequestStore(reopenedDatabase.imageGenerationRequestDao())
                        .get(imageRequest.id)
                )
                val reopenedJob = RoomComfyUiJobStore(reopenedDatabase.comfyUiJobDao())
                    .get(imageJobId)
                assertNotNull(reopenedJob)
                assertEquals(imageRequest.id, reopenedJob!!.requestId)

                val reopenedBytes = reopenedImages.read(storedImage.assetId)
                try {
                    assertEquals(storedImage.byteSize, reopenedBytes.size.toLong())
                    val reopenedDigest = MessageDigest.getInstance("SHA-256")
                        .digest(reopenedBytes)
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    assertEquals(storedImage.sha256, reopenedDigest)
                } finally {
                    reopenedBytes.fill(0)
                }

                val reopenedIntegrity = reopenedDatabase.openHelper.writableDatabase
                    .query("PRAGMA integrity_check").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getString(0)
                    }
                assertEquals("ok", reopenedIntegrity)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            // The owner and provider are unique to this CI invocation. Remove only
            // this test's local data; the backend database and ComfyUI runtime are ephemeral.
            generatedImageAssetId?.let { assetId ->
                runCatching { app.generatedImages.delete(assetId) }
            }
            generatedImageRequestId?.let { requestId ->
                runCatching { app.imageGenerationRequests.delete(requestId) }
            }
            generatedImageJobId?.let { jobId ->
                runCatching { app.comfyUiJobStore.delete(jobId) }
            }
            runCatching { app.conversations.clearForCharacter(storageCharacterId) }
            runCatching { catalog.deleteProvider(llamaCppProviderId) }
            runCatching { catalog.deleteProvider(providerId) }
        }
    }

    private fun openIndependentEncryptedDatabase(context: Context): GalaxyDatabase {
        System.loadLibrary("sqlcipher")
        val appContext = context.applicationContext
        val databaseName = "galaxy.db"
        val databaseFile = appContext.getDatabasePath(databaseName)
        check(databaseFile.isFile) { "The encrypted Galaxy database is missing." }
        val encryptedTemporaryFile = File(
            databaseFile.parentFile,
            databaseFile.name + ".encrypted-tmp"
        )
        val keyStore = EncryptedDatabaseKeyStore(appContext)
        val passphraseProvider = {
            val key = keyStore.getOrCreateDatabaseKey(
                databaseExists = databaseFile.exists(),
                databaseIsPlaintext = false,
                encryptedTemporaryExists = encryptedTemporaryFile.exists()
            )
            try {
                Base64.encode(key, Base64.NO_WRAP)
            } finally {
                key.fill(0)
            }
        }

        return Room.databaseBuilder(appContext, GalaxyDatabase::class.java, databaseName)
            .openHelperFactory(ZeroizingSqlCipherOpenHelperFactory(passphraseProvider))
            .addMigrations(
                GalaxyDatabaseFactory.MIGRATION_1_2,
                GalaxyDatabaseFactory.MIGRATION_2_3,
                GalaxyDatabaseFactory.MIGRATION_3_4,
                GalaxyDatabaseFactory.MIGRATION_4_5,
                GalaxyDatabaseFactory.MIGRATION_5_6
            )
            .build()
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
