package com.pitgpt.galaxy.data.providers.comfyui

import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.data.local.EncryptedDatabaseKeyStore
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import com.pitgpt.galaxy.data.local.ZeroizingSqlCipherOpenHelperFactory
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ConcurrentComfyUiProvidersKeystoreSqlCipherInstrumentedTest {
    @Test
    fun twoProvidersSubmitOneRequestWithAtMostOnePostAcrossIndependentRoomStores() = runBlocking {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_provider_concurrency_wrap_$suffix"
        val preferencesName = "galaxy_test_provider_concurrency_key_$suffix"
        val databaseName = "galaxy-provider-concurrency-$suffix.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val keyStore = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        val issuedPassphrases = mutableListOf<ByteArray>()
        var firstDatabase: GalaxyDatabase? = null
        var secondDatabase: GalaxyDatabase? = null
        val promptId = UUID.nameUUIDFromBytes(
            "provider-concurrency-request-$suffix".toByteArray(Charsets.UTF_8)
        ).toString()
        val transport = BlockingPromptTransport(promptId)

        fun openDatabase(): GalaxyDatabase {
            val opened = Room.databaseBuilder(
                context,
                GalaxyDatabase::class.java,
                databaseName
            )
                .openHelperFactory(
                    ZeroizingSqlCipherOpenHelperFactory(
                        passphraseProvider = {
                            val key = keyStore.getOrCreateDatabaseKey(
                                databaseExists = databaseFile.exists(),
                                databaseIsPlaintext = false,
                                encryptedTemporaryExists = false
                            )
                            try {
                                Base64.encode(key, Base64.NO_WRAP).also(issuedPassphrases::add)
                            } finally {
                                key.fill(0)
                            }
                        }
                    )
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

            try {
                opened.openHelper.writableDatabase
                return opened
            } catch (failure: Throwable) {
                runCatching { opened.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        try {
            val request = ImageGenerationRequest(
                id = "provider-concurrency-request-$suffix",
                conversationId = "provider-concurrency-conversation-$suffix",
                characterId = "provider-concurrency-character",
                prompt = "Synthetic provider concurrency prompt",
                negativePrompt = "Synthetic negative prompt",
                preferredMimeType = "image/png",
                workflowId = "provider-concurrency-workflow",
                width = 64,
                height = 64,
                seed = 42L,
                createdAtEpochMillis = 1_800_000_000_000L
            )
            assertEquals(
                "The fake ComfyUI acknowledgement must use the request's deterministic prompt ID",
                promptId,
                UUID.nameUUIDFromBytes(request.id.toByteArray(Charsets.UTF_8)).toString()
            )

            val first = openDatabase()
            firstDatabase = first
            val second = openDatabase()
            secondDatabase = second
            val firstStore = RoomComfyUiJobStore(first.comfyUiJobDao())
            val secondStore = RoomComfyUiJobStore(second.comfyUiJobDao())

            assertEquals("Each independent helper must receive its own passphrase", 2, issuedPassphrases.size)
            assertTrue(issuedPassphrases.all { it.any { byte -> byte != 0.toByte() } })

            val firstProvider = provider(transport, firstStore)
            val secondProvider = provider(transport, secondStore)

            // Hold the winning provider inside POST /prompt after its durable claim.
            // The second provider must observe SUBMISSION_STARTED and return the same
            // deterministic ID without issuing another POST.
            val firstSubmission = async(Dispatchers.IO) { firstProvider.submit(request) }
            try {
                withTimeout(10_000) { transport.postStarted.await() }
                val secondResult = withTimeout(5_000) { secondProvider.submit(request) }
                assertEquals(promptId, secondResult)
                assertEquals(
                    "The second provider must not issue a duplicate POST while the first acknowledgement is pending",
                    1,
                    transport.postCount.get()
                )
            } finally {
                transport.allowAcknowledgement.complete(Unit)
            }

            assertEquals(promptId, withTimeout(10_000) { firstSubmission.await() })
            assertEquals(listOf("POST"), transport.methods.toList())
            assertEquals(listOf("http://192.168.1.20:8188/prompt"), transport.urls.toList())

            val expected = ComfyUiJobRecord(
                promptId = promptId,
                requestId = request.id,
                createdAtEpochMillis = request.createdAtEpochMillis,
                submissionStarted = true
            )
            assertEquals(expected, firstStore.get(promptId))
            assertEquals(expected, secondStore.get(promptId))
            assertEquals(1, transport.postCount.get())

            first.close()
            firstDatabase = null
            second.close()
            secondDatabase = null
            assertTrue(
                "Each helper's disposable passphrase must be zeroed after its connection pool closes",
                issuedPassphrases.all { buffer -> buffer.all { it == 0.toByte() } }
            )
        } finally {
            transport.allowAcknowledgement.complete(Unit)
            runCatching { secondDatabase?.close() }
            runCatching { firstDatabase?.close() }
            issuedPassphrases.forEach { it.fill(0) }
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    if (containsAlias(wrappingAlias)) deleteEntry(wrappingAlias)
                }
            }
            runCatching { context.deleteSharedPreferences(preferencesName) }
            context.deleteDatabase(databaseName)
            listOf("-wal", "-shm", "-journal").forEach { sidecar ->
                File(databaseFile.path + sidecar).delete()
            }
        }
    }

    private fun provider(
        transport: ComfyUiApiTransport,
        jobs: ComfyUiJobStore
    ) = ComfyUiImageGenerationProvider(
        config = ComfyUiConnectionConfig(baseUrl = "http://192.168.1.20:8188"),
        template = ComfyUiWorkflowTemplate(
            id = "provider-concurrency-workflow",
            workflowJson = """{"1":{"inputs":{"text":"placeholder"}}}""",
            positivePrompt = ComfyUiNodeInput("1", "text")
        ),
        clientId = "galaxy-provider-concurrency-test",
        client = ComfyUiApiClient(transport),
        jobs = jobs
    )

    private class BlockingPromptTransport(
        private val promptId: String
    ) : ComfyUiApiTransport {
        val postStarted = CompletableDeferred<Unit>()
        val allowAcknowledgement = CompletableDeferred<Unit>()
        val postCount = AtomicInteger(0)
        val methods = CopyOnWriteArrayList<String>()
        val urls = CopyOnWriteArrayList<String>()

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
            check(method == "POST") { "Unexpected ComfyUI request in submission-only regression" }
            check(url.endsWith("/prompt")) { "Only POST /prompt is expected" }
            val payload = JSONObject(String(requireNotNull(body), Charsets.UTF_8))
            assertEquals(promptId, payload.getString("prompt_id"))
            postCount.incrementAndGet()
            postStarted.complete(Unit)
            allowAcknowledgement.await()
            return ComfyUiApiResponse(
                statusCode = 200,
                body = """{"prompt_id":"$promptId","number":1}"""
            )
        }

        override suspend fun getBinary(
            url: String,
            bearerToken: String?,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            maxResponseBytes: Int
        ): ComfyUiBinaryResponse = error("Binary downloads are outside this submission regression")
    }
}
