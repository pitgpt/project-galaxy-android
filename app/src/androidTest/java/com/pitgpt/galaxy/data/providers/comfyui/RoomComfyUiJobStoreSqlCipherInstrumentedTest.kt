package com.pitgpt.galaxy.data.providers.comfyui

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.data.repository.RoomImageGenerationRequestStore
import com.pitgpt.galaxy.ui.loadLatestRecoverableImageGenerationRequest
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomComfyUiJobStoreSqlCipherInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var passphrase: ByteArray
    private var database: GalaxyDatabase? = null

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "comfyui-job-store-${UUID.randomUUID()}.db"
        passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
    }

    @After
    fun tearDown() {
        runCatching { database?.close() }
        if (::context.isInitialized && ::databaseName.isInitialized) {
            val file = context.getDatabasePath(databaseName)
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
            File(file.path + "-journal").delete()
        }
        if (::passphrase.isInitialized) passphrase.fill(0)
    }

    @Test
    fun submissionStateSurvivesSqlCipherReopenAndMissingRemoteHistoryDoesNotResubmit() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-durable-123",
            requestId = "galaxy-request-durable-456",
            createdAtEpochMillis = 1_794_230_400_000L
        )
        val generationRequest = ImageGenerationRequest(
            id = "generation-request-durable",
            conversationId = "conversation-durable",
            characterId = "character-durable",
            prompt = "A private prompt that must remain inside SQLCipher",
            negativePrompt = "private negative prompt",
            preferredMimeType = "image/png",
            workflowId = "workflow-v1",
            width = 768,
            height = 1024,
            seed = 42L,
            createdAtEpochMillis = 1_794_230_400_001L
        )
        val activePromptId = UUID.nameUUIDFromBytes(
            generationRequest.id.toByteArray(Charsets.UTF_8)
        ).toString()
        val activeRecord = ComfyUiJobRecord(
            promptId = activePromptId,
            requestId = generationRequest.id,
            createdAtEpochMillis = generationRequest.createdAtEpochMillis
        )

        val firstDatabase = openDatabase()
        database = firstDatabase
        val firstStore = RoomComfyUiJobStore(firstDatabase.comfyUiJobDao())
        firstStore.put(record)
        assertTrue(firstStore.markSubmissionStarted(record.promptId))
        firstStore.markCancelled(record.promptId)
        RoomImageGenerationRequestStore(firstDatabase.imageGenerationRequestDao()).put(generationRequest)

        // Simulate an ambiguous acknowledgement after the durable claim. The provider must
        // leave SUBMISSION_STARTED in SQLCipher rather than making the request safe to retry.
        val firstTransport = RecordingTransport(
            failure = IOException("simulated lost submission acknowledgement")
        )
        val uncertain = try {
            createProvider(firstTransport, firstStore).submit(generationRequest)
            throw AssertionError("The ambiguous transport failure must remain unresolved")
        } catch (failure: ComfyUiSubmissionUncertainException) {
            failure
        }
        assertEquals(activePromptId, uncertain.promptId)
        assertEquals(listOf("POST"), firstTransport.methods)
        assertEquals(activeRecord.copy(submissionStarted = true), firstStore.get(activePromptId))

        firstDatabase.close()
        database = null

        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue("The SQLCipher database file must exist after closing Room", databaseFile.isFile)
        val header = ByteArray(16)
        FileInputStream(databaseFile).use { input ->
            assertEquals(16, input.read(header))
        }
        assertFalse(
            "The persisted Room database must not expose the plaintext SQLite header",
            String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
        )
        val encryptedBytes = databaseFile.readBytes()
        val encryptedText = String(encryptedBytes, Charsets.ISO_8859_1)
        assertFalse("Prompt text must not appear in the SQLCipher file", encryptedText.contains("A private prompt"))
        assertFalse("Negative prompt text must not appear in the SQLCipher file", encryptedText.contains("private negative prompt"))

        val reopenedDatabase = openDatabase()
        database = reopenedDatabase
        val reopenedStore = RoomComfyUiJobStore(reopenedDatabase.comfyUiJobDao())
        val expected = record.copy(cancelled = true, submissionStarted = true)
        val expectedActive = activeRecord.copy(submissionStarted = true)
        assertEquals(expected, reopenedStore.get(record.promptId))
        assertEquals(expectedActive, reopenedStore.get(activePromptId))
        assertEquals(listOf(expected, expectedActive), reopenedStore.all())

        val reopenedRequests = RoomImageGenerationRequestStore(reopenedDatabase.imageGenerationRequestDao())
        assertEquals(generationRequest, reopenedRequests.get(generationRequest.id))
        assertEquals(listOf(generationRequest), reopenedRequests.all())

        // Exercise the same correlation path used by the restored generation panel,
        // against the reopened SQLCipher-backed stores rather than in-memory fakes.
        val restored = loadLatestRecoverableImageGenerationRequest(
            characterId = generationRequest.characterId,
            requestStore = reopenedRequests,
            jobStore = reopenedStore
        )
        assertEquals(generationRequest, restored?.request)
        assertEquals(expectedActive, restored?.job)
        assertFalse(restored?.canResumeSafely ?: true)

        // The new provider first reconciles the persisted prompt against ComfyUI after reopen.
        // A queue miss followed by an empty history response is ambiguous, not permission to resubmit.
        val resumedTransport = RecordingTransport(
            responses = listOf(
                ComfyUiApiResponse(200, """{"queue_running":[],"queue_pending":[]}"""),
                ComfyUiApiResponse(200, "{}")
            )
        )
        val resumedProvider = createProvider(resumedTransport, reopenedStore)
        val missingRemoteJob = try {
            resumedProvider.status(activePromptId)
            throw AssertionError("A prompt missing from both queue and history must remain unresolved")
        } catch (failure: ComfyUiApiException) {
            failure
        }
        assertEquals(null, missingRemoteJob.httpStatusCode)
        assertEquals(listOf("GET", "GET"), resumedTransport.methods)
        assertEquals(
            listOf(
                "http://192.168.1.20:8188/queue",
                "http://192.168.1.20:8188/history/$activePromptId"
            ),
            resumedTransport.urls
        )
        assertEquals(expectedActive, reopenedStore.get(activePromptId))

        // Retrying the same durable request still returns its prompt ID without a second POST.
        assertEquals(activePromptId, resumedProvider.submit(requireNotNull(restored).request))
        assertEquals(listOf("GET", "GET"), resumedTransport.methods)
        assertEquals(expectedActive, reopenedStore.get(activePromptId))
    }

    private fun createProvider(
        transport: RecordingTransport,
        store: ComfyUiJobStore
    ) = ComfyUiImageGenerationProvider(
        config = ComfyUiConnectionConfig("http://192.168.1.20:8188"),
        template = ComfyUiWorkflowTemplate(
            id = "workflow-v1",
            workflowJson = """{"1":{"inputs":{"text":"placeholder"}}}""",
            positivePrompt = ComfyUiNodeInput("1", "text")
        ),
        clientId = "galaxy-recovery-test",
        client = ComfyUiApiClient(transport),
        jobs = store
    )

    private class RecordingTransport(
        private val responses: List<ComfyUiApiResponse> = emptyList(),
        private val failure: Exception? = null
    ) : ComfyUiApiTransport {
        val methods = mutableListOf<String>()
        val urls = mutableListOf<String>()
        private var responseIndex = 0

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
            urls += url
            failure?.let { throw it }
            return responses.getOrNull(responseIndex++)
                ?: ComfyUiApiResponse(200, "{}")
        }

        override suspend fun getBinary(
            url: String,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiBinaryResponse {
            throw AssertionError("Image download is not expected during submission recovery")
        }
    }

    private fun openDatabase(): GalaxyDatabase {
        val keyBytes = Base64.encode(passphrase, Base64.NO_WRAP)
        val opened = Room.databaseBuilder(context, GalaxyDatabase::class.java, databaseName)
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
}
