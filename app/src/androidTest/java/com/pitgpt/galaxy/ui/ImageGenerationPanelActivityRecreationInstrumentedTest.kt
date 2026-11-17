package com.pitgpt.galaxy.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.MainActivity
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageGenerationPanelActivityRecreationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityRecreationRestoresSubmittedJobFromSqlCipherWithoutResubmitting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as GalaxyApplication
        val conversationId = "activity-recreation-${UUID.randomUUID()}"
        val request = ImageGenerationRequest(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            characterId = conversationId,
            prompt = "Synthetic activity recreation recovery regression",
            width = 64,
            height = 64,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        val promptId = UUID.nameUUIDFromBytes(request.id.toByteArray(Charsets.UTF_8)).toString()
        val job = ComfyUiJobRecord(
            promptId = promptId,
            requestId = request.id,
            createdAtEpochMillis = request.createdAtEpochMillis,
            submissionStarted = true
        )
        val statusPollCount = AtomicInteger()
        val promptSubmissionCount = AtomicInteger()

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
                if (method == "POST" && url.endsWith("/prompt")) {
                    promptSubmissionCount.incrementAndGet()
                    return ComfyUiApiResponse(
                        statusCode = 200,
                        body = """{"prompt_id":"$promptId","number":1}"""
                    )
                }
                if (method == "GET" && url.endsWith("/queue")) {
                    statusPollCount.incrementAndGet()
                }
                return ComfyUiApiResponse(
                    statusCode = 200,
                    body = """{"queue_running":[[0,"$promptId",{}]],"queue_pending":[]}"""
                )
            }

            override suspend fun getBinary(
                url: String,
                bearerToken: String?,
                connectTimeoutMillis: Int,
                readTimeoutMillis: Int,
                maxResponseBytes: Int
            ): ComfyUiBinaryResponse = error("Binary downloads are not used by this recovery regression")
        }

        val config = ComfyUiConnectionConfig(baseUrl = "https://comfy.example.test")
        val template = ComfyUiWorkflowTemplate(
            id = "activity-recreation-test",
            workflowJson = """{"1":{"inputs":{"text":""}}}""",
            positivePrompt = ComfyUiNodeInput("1", "text")
        )

        fun installGenerationPanel() {
            composeRule.activityRule.scenario.onActivity { activity ->
                activity.setContent {
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
                }
            }
        }

        try {
            application.imageGenerationRequests.put(request)
            application.comfyUiJobStore.put(job)

            installGenerationPanel()
            composeRule.waitUntil(timeoutMillis = 20_000) {
                statusPollCount.get() >= 1 &&
                    composeRule.onAllNodesWithText("Статус: Выполняется")
                        .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Статус: Выполняется").assertExists()
            composeRule.onNodeWithText("ПРОДОЛЖИТЬ СОХРАНЁННУЮ ОТПРАВКУ").assertDoesNotExist()
            assertEquals("A recovered submitted job must not be submitted again", 0, promptSubmissionCount.get())

            val pollsBeforeRecreation = statusPollCount.get()
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            val pollsAfterRecreation = statusPollCount.get()
            assertEquals("Activity recreation must dispose the previous polling composition", pollsBeforeRecreation, pollsAfterRecreation)

            installGenerationPanel()
            composeRule.waitUntil(timeoutMillis = 20_000) {
                statusPollCount.get() > pollsAfterRecreation &&
                    composeRule.onAllNodesWithText("Статус: Выполняется")
                        .fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals("The original request must be restored from SQLCipher", request, application.imageGenerationRequests.get(request.id))
            assertEquals("The submitted-job journal must survive Activity recreation", job, application.comfyUiJobStore.get(promptId))
            composeRule.onNodeWithText("Статус: Выполняется").assertExists()
            composeRule.onNodeWithText("ПРОДОЛЖИТЬ СОХРАНЁННУЮ ОТПРАВКУ").assertDoesNotExist()
            assertEquals("Activity recreation must not issue another POST /prompt", 0, promptSubmissionCount.get())
        } finally {
            runCatching {
                composeRule.activityRule.scenario.onActivity { activity ->
                    activity.setContent { Text("TEST FINISHED") }
                }
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithText("TEST FINISHED")
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            runCatching { application.imageGenerationRequests.delete(request.id) }
            runCatching { application.comfyUiJobStore.delete(promptId) }
        }
    }
}
