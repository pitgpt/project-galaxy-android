package com.pitgpt.galaxy.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiApiClient
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiApiResponse
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiApiTransport
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiBinaryResponse
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiConnectionConfig
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRuntimeFactory
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedImageHistoryPollingInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun comfyUiStatusPollingContinuesWhileEncryptedHistoryIsVisible() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as GalaxyApplication
        val conversationId = "history-poll-${UUID.randomUUID()}"
        val promptId = "poll-${UUID.randomUUID()}"
        val request = ImageGenerationRequest(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            characterId = conversationId,
            prompt = "Synthetic status-polling navigation regression",
            width = 64,
            height = 64,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        val statusPollCount = AtomicInteger()

        val transport = object : ComfyUiApiTransport {
            override suspend fun execute(
                method: String,
                url: String,
                body: ByteArray?,
                bearerToken: String?,
                connectTimeoutMillis: Int,
                readTimeoutMillis: Int,
                maxResponseBytes: Int
            ): ComfyUiApiResponse {
                statusPollCount.incrementAndGet()
                return ComfyUiApiResponse(
                    statusCode = 200,
                    body = """{"queue_running":[[0,"${promptId}",{}]],"queue_pending":[]}"""
                )
            }

            override suspend fun getBinary(
                url: String,
                bearerToken: String?,
                connectTimeoutMillis: Int,
                readTimeoutMillis: Int,
                maxResponseBytes: Int
            ): ComfyUiBinaryResponse = error("Binary downloads are not used by this polling regression")
        }

        val config = ComfyUiConnectionConfig(baseUrl = "https://comfy.example.test")
        val template = ComfyUiWorkflowTemplate(
            id = "history-poll-test",
            workflowJson = """{"1":{"inputs":{"text":""}}}""",
            positivePrompt = ComfyUiNodeInput("1", "text")
        )
        val showPanel = mutableStateOf(true)
        var contentSet = false

        try {
            application.imageGenerationRequests.put(request)
            application.comfyUiJobStore.put(
                ComfyUiJobRecord(
                    promptId = promptId,
                    requestId = request.id,
                    createdAtEpochMillis = request.createdAtEpochMillis,
                    submissionStarted = true
                )
            )

            composeRule.setContent {
                if (showPanel.value) {
                    ImageGenerationPanel(
                        app = application,
                        characterId = conversationId,
                        onSubmittingChanged = {},
                        runtimeFactory = { runtimeApp, clientId ->
                            ComfyUiRuntimeFactory(
                                jobs = runtimeApp.comfyUiJobStore,
                                images = runtimeApp.generatedImages,
                                client = ComfyUiApiClient(transport)
                            ).create(config, template, clientId)
                        }
                    )
                } else {
                    Text("TEST FINISHED")
                }
            }
            contentSet = true

            composeRule.waitUntil(timeoutMillis = 15_000) {
                statusPollCount.get() >= 1
            }
            composeRule.onNodeWithText("Статус: Выполняется").assertExists()

            composeRule.onNodeWithText("ИСТОРИЯ").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("ИСТОРИЯ ИЗОБРАЖЕНИЙ")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val pollsAfterHistoryOpened = statusPollCount.get()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                statusPollCount.get() > pollsAfterHistoryOpened
            }
            composeRule.onNodeWithText("ИСТОРИЯ ИЗОБРАЖЕНИЙ").assertExists()
            composeRule.onNodeWithText("Сохранённых изображений пока нет.").assertExists()

            composeRule.onNodeWithText("‹  НАЗАД").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("ГЕНЕРАЦИЯ ИЗОБРАЖЕНИЯ")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Статус: Выполняется").assertExists()
        } finally {
            if (contentSet) {
                runCatching {
                    composeRule.runOnIdle { showPanel.value = false }
                    composeRule.waitForIdle()
                }
            }
            runCatching { application.imageGenerationRequests.delete(request.id) }
            runCatching { application.comfyUiJobStore.delete(promptId) }
        }
    }
}
