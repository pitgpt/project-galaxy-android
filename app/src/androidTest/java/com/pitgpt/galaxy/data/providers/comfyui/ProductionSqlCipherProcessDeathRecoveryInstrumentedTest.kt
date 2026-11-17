package com.pitgpt.galaxy.data.providers.comfyui

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.GalaxyApplication
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.ui.loadLatestRecoverableImageGenerationRequest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Two Orchestrator-separated invocations exercise the production SQLCipher/Keystore path.
 * The marker contains only a test PID and opaque fixture token; no prompt or key material.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProductionSqlCipherProcessDeathRecoveryInstrumentedTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val markerFile: File
        get() = File(context.noBackupFilesDir, MARKER_NAME)

    @Test
    fun aPersistSubmittedJobBeforeOrchestratorRestartsProductionProcess() = runBlocking {
        val app = context.applicationContext as GalaxyApplication
        cleanupPreviousFixture(app)

        val fixture = Fixture(
            token = UUID.randomUUID().toString(),
            processId = Process.myPid()
        )
        val request = generationRequest(fixture.token)
        val job = jobRecord(request)

        app.imageGenerationRequests.put(request)
        app.comfyUiJobStore.put(job)
        assertTrue(
            "The production SQLCipher store must commit the submission claim",
            app.comfyUiJobStore.markSubmissionStarted(job.promptId)
        )

        val sqlDatabase = app.database.openHelper.writableDatabase
        val journalMode = sqlDatabase.query("PRAGMA journal_mode").use { cursor ->
            assertTrue("The production database must expose its journal mode", cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("The process-death regression requires WAL mode", "wal", journalMode.lowercase())

        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        assertTrue("The production SQLCipher database must exist", databaseFile.isFile)
        val walFile = File(databaseFile.path + "-wal")
        assertTrue(
            "Committed production WAL frames must exist before process termination",
            walFile.isFile && walFile.length() > 32L
        )

        val header = ByteArray(16)
        try {
            FileInputStream(databaseFile).use { input ->
                assertEquals("The encrypted database header must be readable", 16, input.read(header))
            }
            assertFalse(
                "The production database must not expose the plaintext SQLite header",
                String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
            )
        } finally {
            header.fill(0)
        }

        // Written last: the next Orchestrator invocation may rely on this fixture only
        // after both Room rows and the WAL-backed submission claim have committed.
        writeFixture(fixture)
        // Intentionally leave the production Room helper open. Orchestrator must kill
        // this target process before running the second method in a fresh process.
    }

    @Test
    fun bRecoverSubmittedJobAndDoNotResubmitAfterProductionProcessDeath() = runBlocking {
        val app = context.applicationContext as GalaxyApplication
        val fixture = readFixture()

        try {
            assertNotEquals(
                "Android Test Orchestrator must restart the target application process",
                fixture.processId,
                Process.myPid()
            )

            val request = generationRequest(fixture.token)
            val expectedJob = jobRecord(request).copy(submissionStarted = true)
            val database = app.database
            val integrityResult = database.openHelper.writableDatabase
                .query("PRAGMA integrity_check")
                .use { cursor ->
                    assertTrue("SQLCipher integrity_check must return a row", cursor.moveToFirst())
                    cursor.getString(0)
                }
            assertEquals("ok", integrityResult.lowercase())

            assertEquals(
                "The generation request must survive production process death",
                request,
                app.imageGenerationRequests.get(request.id)
            )
            assertEquals(
                "The SQLCipher ComfyUI submission claim must survive process death",
                expectedJob,
                app.comfyUiJobStore.get(expectedJob.promptId)
            )

            val recovered = loadLatestRecoverableImageGenerationRequest(
                characterId = request.characterId,
                requestStore = app.imageGenerationRequests,
                jobStore = app.comfyUiJobStore
            )
            assertNotNull("The production recovery query must find the submitted request", recovered)
            assertEquals(request, recovered?.request)
            assertEquals(expectedJob, recovered?.job)
            assertFalse(
                "A recovered SUBMISSION_STARTED job must not be classified as safe to resubmit",
                recovered?.canResumeSafely ?: true
            )
            assertFalse(
                "A recovered submission claim must not be acquired a second time",
                app.comfyUiJobStore.markSubmissionStarted(expectedJob.promptId)
            )

            val transport = RecordingTransport()
            val provider = ComfyUiImageGenerationProvider(
                config = ComfyUiConnectionConfig(baseUrl = "https://comfy.example.test"),
                template = ComfyUiWorkflowTemplate(
                    id = "process-death-recovery-workflow",
                    workflowJson = """{"1":{"inputs":{"text":"placeholder"}}}""",
                    positivePrompt = ComfyUiNodeInput("1", "text")
                ),
                clientId = "galaxy-process-death-test",
                client = ComfyUiApiClient(transport),
                jobs = app.comfyUiJobStore
            )
            assertEquals(
                "The recovered provider must return the original deterministic prompt ID",
                expectedJob.promptId,
                provider.submit(request)
            )
            assertTrue(
                "A recovered SUBMISSION_STARTED job must not issue any HTTP request",
                transport.methods.isEmpty()
            )
        } finally {
            cleanupFixture(app, fixture)
        }
    }

    private fun generationRequest(token: String) = ImageGenerationRequest(
        id = "$REQUEST_ID_PREFIX$token",
        conversationId = "$CONVERSATION_ID_PREFIX$token",
        characterId = "$CHARACTER_ID_PREFIX$token",
        prompt = "Synthetic process-death positive prompt",
        negativePrompt = "Synthetic process-death negative prompt",
        preferredMimeType = "image/png",
        workflowId = "process-death-recovery-workflow",
        width = 64,
        height = 64,
        seed = 42L,
        createdAtEpochMillis = CREATED_AT
    )

    private fun jobRecord(request: ImageGenerationRequest): ComfyUiJobRecord {
        val promptId = UUID.nameUUIDFromBytes(request.id.toByteArray(Charsets.UTF_8)).toString()
        return ComfyUiJobRecord(
            promptId = promptId,
            requestId = request.id,
            createdAtEpochMillis = request.createdAtEpochMillis
        )
    }

    private fun writeFixture(fixture: Fixture) {
        val properties = Properties().apply {
            setProperty("processId", fixture.processId.toString())
            setProperty("token", fixture.token)
        }
        FileOutputStream(markerFile).use { output ->
            properties.store(output, "Galaxy process-death instrumentation fixture")
            output.fd.sync()
        }
    }

    private fun readFixture(): Fixture {
        val properties = Properties()
        FileInputStream(markerFile).use { input -> properties.load(input) }
        val token = requireNotNull(properties.getProperty("token"))
        require(UUID.fromString(token).toString() == token) { "Invalid process-death fixture token" }
        val processId = requireNotNull(properties.getProperty("processId")).toInt()
        return Fixture(token, processId)
    }

    private suspend fun cleanupPreviousFixture(app: GalaxyApplication) {
        if (!markerFile.isFile) return
        val previous = runCatching { readFixture() }.getOrNull()
        if (previous == null) {
            markerFile.delete()
            return
        }
        cleanupFixture(app, previous)
    }

    private suspend fun cleanupFixture(app: GalaxyApplication, fixture: Fixture) {
        val request = generationRequest(fixture.token)
        val promptId = jobRecord(request).promptId
        if (app.comfyUiJobStore.get(promptId) != null) {
            app.comfyUiJobStore.delete(promptId)
        }
        if (app.imageGenerationRequests.get(request.id) != null) {
            app.imageGenerationRequests.delete(request.id)
        }
        markerFile.delete()
    }

    private data class Fixture(
        val token: String,
        val processId: Int
    )

    private class RecordingTransport : ComfyUiApiTransport {
        val methods = mutableListOf<String>()

        override suspend fun execute(
            method: String,
            url: String,
            body: ByteArray?,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiApiResponse {
            methods += method
            return ComfyUiApiResponse(statusCode = 200, body = "{}")
        }

        override suspend fun getBinary(
            url: String,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiBinaryResponse = error("No binary request is expected during recovery")
    }

    private companion object {
        const val DATABASE_NAME = "galaxy.db"
        const val MARKER_NAME = "galaxy-production-process-death.pid"
        const val REQUEST_ID_PREFIX = "process-death-request-"
        const val CONVERSATION_ID_PREFIX = "process-death-conversation-"
        const val CHARACTER_ID_PREFIX = "process-death-character-"
        const val CREATED_AT = 1_794_231_000_000L
    }
}
