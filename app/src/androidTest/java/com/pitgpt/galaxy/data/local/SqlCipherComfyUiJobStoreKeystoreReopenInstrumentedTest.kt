package com.pitgpt.galaxy.data.local

import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.RoomComfyUiJobStore
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlCipherComfyUiJobStoreKeystoreReopenInstrumentedTest {
    @Test
    fun comfyUiJobStoreSurvivesKeystoreBackedRoomCloseAndReopen() = runBlocking {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_job_store_wrap_$suffix"
        val preferencesName = "galaxy_test_job_store_key_$suffix"
        val databaseName = "galaxy-job-store-keystore-$suffix.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val keyStore = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        val issuedPassphrases = mutableListOf<ByteArray>()
        var database: GalaxyDatabase? = null

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
            val original = ComfyUiJobRecord(
                promptId = "prompt-$suffix",
                requestId = "request-$suffix",
                createdAtEpochMillis = 1_794_230_400_000L
            )

            val firstDatabase = openDatabase()
            database = firstDatabase
            val firstStore = RoomComfyUiJobStore(firstDatabase.comfyUiJobDao())
            firstStore.put(original)
            assertTrue(firstStore.markSubmissionStarted(original.promptId))
            firstStore.markCancelled(original.promptId)

            val expected = original.copy(
                cancelled = true,
                submissionStarted = true
            )
            assertEquals(expected, firstStore.get(original.promptId))
            assertEquals(listOf(expected), firstStore.all())
            assertEquals(1, issuedPassphrases.size)
            assertTrue(
                "The passphrase must remain valid while Room's SQLCipher connection pool is open",
                issuedPassphrases.single().any { it != 0.toByte() }
            )

            firstDatabase.close()
            database = null
            assertTrue(
                "Closing Room must zero the passphrase after its connection pool closes",
                issuedPassphrases.single().all { it == 0.toByte() }
            )
            assertTrue("The encrypted Room database must remain on disk", databaseFile.isFile)

            val header = ByteArray(16)
            try {
                FileInputStream(databaseFile).use { input ->
                    assertEquals(16, input.read(header))
                }
                assertFalse(
                    "The SQLCipher database must not expose the plaintext SQLite header",
                    String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
                )
            } finally {
                header.fill(0)
            }

            val reopenedDatabase = openDatabase()
            database = reopenedDatabase
            assertEquals(
                "Reopening must obtain a freshly unwrapped passphrase",
                2,
                issuedPassphrases.size
            )
            assertTrue(
                "The reopened helper must retain its fresh passphrase while open",
                issuedPassphrases[1].any { it != 0.toByte() }
            )

            val reopenedStore = RoomComfyUiJobStore(reopenedDatabase.comfyUiJobDao())
            assertEquals(expected, reopenedStore.get(original.promptId))
            assertEquals(listOf(expected), reopenedStore.all())
            assertFalse(
                "A durable SUBMISSION_STARTED job must not be claimable again",
                reopenedStore.markSubmissionStarted(original.promptId)
            )
            assertEquals(expected, reopenedStore.get(original.promptId))

            reopenedDatabase.close()
            database = null
            assertTrue(
                "Closing the reopened Room helper must zero its passphrase",
                issuedPassphrases[1].all { it == 0.toByte() }
            )
        } finally {
            runCatching { database?.close() }
            issuedPassphrases.forEach { it.fill(0) }
            runCatching {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply {
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


    @Test
    fun concurrentKeystoreBackedRoomStoresClaimSubmissionAtMostOnceAcrossInstances() = runBlocking {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_concurrent_job_store_wrap_$suffix"
        val preferencesName = "galaxy_test_concurrent_job_store_key_$suffix"
        val databaseName = "galaxy-concurrent-job-store-$suffix.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val keyStore = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        val issuedPassphrases = mutableListOf<ByteArray>()
        var firstDatabase: GalaxyDatabase? = null
        var secondDatabase: GalaxyDatabase? = null
        var reopenedDatabase: GalaxyDatabase? = null

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
            val record = ComfyUiJobRecord(
                promptId = "prompt-$suffix",
                requestId = "request-$suffix",
                createdAtEpochMillis = 1_794_230_500_000L
            )

            val first = openDatabase()
            firstDatabase = first
            val second = openDatabase()
            secondDatabase = second
            val firstStore = RoomComfyUiJobStore(first.comfyUiJobDao())
            val secondStore = RoomComfyUiJobStore(second.comfyUiJobDao())
            firstStore.put(record)

            assertEquals("Each independent Room helper must obtain its own disposable passphrase", 2, issuedPassphrases.size)
            assertTrue(issuedPassphrases.all { buffer -> buffer.any { it != 0.toByte() } })

            val startGate = CompletableDeferred<Unit>()
            val claims = coroutineScope {
                val workers = (0 until 32).map { index ->
                    async(Dispatchers.IO) {
                        startGate.await()
                        val store = if (index % 2 == 0) firstStore else secondStore
                        store.markSubmissionStarted(record.promptId)
                    }
                }
                startGate.complete(Unit)
                workers.awaitAll()
            }

            assertEquals(
                "Exactly one caller across both Keystore-backed SQLCipher Room instances may claim PREPARED",
                1,
                claims.count { it }
            )
            val expected = record.copy(submissionStarted = true)
            assertEquals(expected, firstStore.get(record.promptId))
            assertEquals(expected, secondStore.get(record.promptId))

            first.close()
            firstDatabase = null
            second.close()
            secondDatabase = null
            assertTrue(
                "Closing each Room helper must zero its own passphrase buffer",
                issuedPassphrases.all { buffer -> buffer.all { it == 0.toByte() } }
            )

            val reopened = openDatabase()
            reopenedDatabase = reopened
            assertEquals("A fresh Keystore unwrap must supply the reopened helper", 3, issuedPassphrases.size)
            val reopenedStore = RoomComfyUiJobStore(reopened.comfyUiJobDao())
            assertEquals(expected, reopenedStore.get(record.promptId))
            assertFalse(
                "The durable claim must remain unavailable after a fresh Keystore-backed reopen",
                reopenedStore.markSubmissionStarted(record.promptId)
            )
            assertEquals(expected, reopenedStore.get(record.promptId))

            reopened.close()
            reopenedDatabase = null
            assertTrue(
                "Closing the reopened helper must zero its fresh passphrase buffer",
                issuedPassphrases.last().all { it == 0.toByte() }
            )
        } finally {
            runCatching { reopenedDatabase?.close() }
            runCatching { secondDatabase?.close() }
            runCatching { firstDatabase?.close() }
            issuedPassphrases.forEach { it.fill(0) }
            runCatching {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply {
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

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
