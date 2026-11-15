package com.pitgpt.galaxy.data.providers.comfyui

import android.content.Context
import android.os.Process
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import com.pitgpt.galaxy.data.repository.RoomImageGenerationRequestStore
import com.pitgpt.galaxy.ui.loadLatestRecoverableImageGenerationRequest
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SqlCipherProcessDeathRecoveryInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun aPersistSubmissionClaimBeforeOrchestratorRestartsTargetProcess() = runBlocking {
        cleanupFixture()

        val request = generationRequest()
        val record = jobRecord(request)
        val opened = openDatabase(testPassphrase())
        ProcessDeathDatabaseHolder.database = opened

        val requestStore = RoomImageGenerationRequestStore(opened.imageGenerationRequestDao())
        val jobStore = RoomComfyUiJobStore(opened.comfyUiJobDao())
        requestStore.put(request)
        jobStore.put(record)
        assertTrue(
            "The submission claim must commit before the orchestrator stops the target process",
            jobStore.markSubmissionStarted(record.promptId)
        )

        val journalMode = opened.openHelper.writableDatabase.query("PRAGMA journal_mode").use { cursor ->
            assertTrue("The SQLCipher database must expose its journal mode", cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("The process-death scenario requires WAL mode", "wal", journalMode.lowercase())

        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val walFile = File(databaseFile.path + "-wal")
        assertTrue("The database must be present before process termination", databaseFile.isFile)
        assertTrue("Committed WAL frames must remain before process termination", walFile.isFile && walFile.length() > 32L)

        FileOutputStream(processMarker).use { output ->
            output.write(Process.myPid().toString().toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }

        // Intentionally keep Room open. Android Test Orchestrator must terminate this
        // target process before invoking the next test method in a fresh process.
    }

    @Test
    fun bRecoverSubmissionClaimAfterOrchestratorRestartsTargetProcess() = runBlocking {
        try {
            assertTrue(
                "The first orchestrated test must leave a process marker",
                processMarker.isFile
            )
            val previousPid = processMarker.readText(Charsets.US_ASCII).trim().toInt()
            assertNotEquals(
                "Android Test Orchestrator must restart the target app process between test methods",
                previousPid,
                Process.myPid()
            )

            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            val walFile = File(databaseFile.path + "-wal")
            assertTrue("The SQLCipher database must survive target process termination", databaseFile.isFile)
            assertTrue("The committed WAL must survive target process termination", walFile.isFile && walFile.length() > 32L)

            val reopened = openDatabase(testPassphrase())
            ProcessDeathDatabaseHolder.database = reopened
            val integrityResult = reopened.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
                assertTrue("SQLCipher integrity_check must return a row", cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("ok", integrityResult.lowercase())

            val request = generationRequest()
            val expectedRecord = jobRecord(request).copy(submissionStarted = true)
            val jobStore = RoomComfyUiJobStore(reopened.comfyUiJobDao())
            val requestStore = RoomImageGenerationRequestStore(reopened.imageGenerationRequestDao())

            assertEquals(
                "The durable submission claim must survive actual target-process termination",
                expectedRecord,
                jobStore.get(expectedRecord.promptId)
            )
            assertEquals(
                "The full generation request must survive actual target-process termination",
                request,
                requestStore.get(request.id)
            )
            val recovered = loadLatestRecoverableImageGenerationRequest(
                characterId = request.characterId,
                requestStore = requestStore,
                jobStore = jobStore
            )
            assertEquals(request, recovered?.request)
            assertEquals(expectedRecord, recovered?.job)
            assertFalse(
                "A recovered SUBMISSION_STARTED request must not be classified as safe to resume",
                recovered?.canResumeSafely ?: true
            )
            assertFalse(
                "A recovered submission claim must not be acquired again",
                jobStore.markSubmissionStarted(expectedRecord.promptId)
            )

            // Recreate the production provider in the new process. Its deterministic prompt ID
            // must resolve to the recovered SUBMISSION_STARTED row before any HTTP request.
            val resumedTransport = RecordingTransport()
            val resumedProvider = createProvider(resumedTransport, jobStore)
            assertEquals(
                "The recovered provider must return the original ComfyUI prompt ID",
                expectedRecord.promptId,
                resumedProvider.submit(request)
            )
            assertFalse(
                "A recovered SUBMISSION_STARTED request must not issue POST /prompt",
                resumedTransport.methods.contains("POST")
            )
            assertTrue(
                "A recovered SUBMISSION_STARTED request must not make any HTTP request",
                resumedTransport.methods.isEmpty()
            )
        } finally {
            cleanupFixture()
        }
    }

    private fun createProvider(
        transport: RecordingTransport,
        store: ComfyUiJobStore
    ) = ComfyUiImageGenerationProvider(
        config = ComfyUiConnectionConfig("http://127.0.0.1:8188"),
        template = ComfyUiWorkflowTemplate(
            id = "process-death-workflow",
            workflowJson = """{"1":{"inputs":{"text":"placeholder"}}}""",
            positivePrompt = ComfyUiNodeInput("1", "text")
        ),
        clientId = "galaxy-process-death-test",
        client = ComfyUiApiClient(transport),
        jobs = store
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
            return ComfyUiApiResponse(200, "{}")
        }

        override suspend fun getBinary(
            url: String,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiBinaryResponse {
            throw AssertionError("No binary request is expected during submission recovery")
        }
    }

    private fun generationRequest() = ImageGenerationRequest(
        id = REQUEST_ID,
        conversationId = "process-death-conversation",
        characterId = "process-death-character",
        prompt = "synthetic process-death positive prompt",
        negativePrompt = "synthetic process-death negative prompt",
        preferredMimeType = "image/png",
        workflowId = "process-death-workflow",
        width = 512,
        height = 768,
        seed = 42L,
        createdAtEpochMillis = CREATED_AT
    )

    private fun jobRecord(request: ImageGenerationRequest) = ComfyUiJobRecord(
        promptId = UUID.nameUUIDFromBytes(request.id.toByteArray(Charsets.UTF_8)).toString(),
        requestId = request.id,
        createdAtEpochMillis = request.createdAtEpochMillis
    )

    private fun openDatabase(passphrase: ByteArray): GalaxyDatabase {
        val keyBytes = Base64.encode(passphrase, Base64.NO_WRAP)
        val opened = Room.databaseBuilder(context, GalaxyDatabase::class.java, DATABASE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(SupportOpenHelperFactory(keyBytes, null, true))
            .build()
        try {
            opened.openHelper.writableDatabase
            return opened
        } catch (failure: Exception) {
            keyBytes.fill(0)
            runCatching { opened.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun testPassphrase(): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("galaxy-sqlcipher-process-death-test-v1".toByteArray(Charsets.UTF_8))

    private val processMarker: File
        get() = File(context.noBackupFilesDir, MARKER_NAME)

    private fun cleanupFixture() {
        runCatching { ProcessDeathDatabaseHolder.database?.close() }
        ProcessDeathDatabaseHolder.database = null
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-journal").delete()
        processMarker.delete()
    }

    private companion object {
        const val DATABASE_NAME = "galaxy-process-death-recovery.db"
        const val MARKER_NAME = "galaxy-process-death-recovery.pid"
        const val REQUEST_ID = "process-death-generation-request"
        const val CREATED_AT = 1_794_230_800_000L
    }
}

private object ProcessDeathDatabaseHolder {
    var database: GalaxyDatabase? = null
}
