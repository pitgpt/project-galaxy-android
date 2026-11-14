package com.pitgpt.galaxy.data.providers.comfyui

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
class RoomComfyUiJobStoreRollbackJournalInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var passphrase: ByteArray
    private val encodedPassphrases = mutableListOf<ByteArray>()
    private var database: GalaxyDatabase? = null

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "comfyui-job-journal-${UUID.randomUUID()}.db"
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
        encodedPassphrases.forEach { it.fill(0) }
        encodedPassphrases.clear()
        if (::passphrase.isInitialized) passphrase.fill(0)
    }

    @Test
    fun rollbackJournalDoesNotExposeJobIdentifiersDuringUncommittedUpdate() = runBlocking {
        val originalRequestId = "galaxy-request-rollback-journal-original-456"
        val replacementRequestId = "galaxy-request-rollback-journal-replacement-789"
        val record = ComfyUiJobRecord(
            promptId = "prompt-rollback-journal-123",
            requestId = originalRequestId,
            createdAtEpochMillis = 1_794_230_400_000L
        )

        val openedDatabase = openDatabase(RoomDatabase.JournalMode.TRUNCATE)
        database = openedDatabase
        val store = RoomComfyUiJobStore(openedDatabase.comfyUiJobDao())
        store.put(record)

        val sqlDatabase = openedDatabase.openHelper.writableDatabase
        val journalMode = sqlDatabase.query("PRAGMA journal_mode").use { cursor ->
            assertTrue("The SQLCipher database must report a journal mode", cursor.moveToFirst())
            cursor.getString(0)
        }
        assertTrue(
            "The regression requires a non-WAL SQLite rollback-journal mode",
            journalMode.lowercase() in setOf("delete", "truncate", "persist")
        )

        val databaseFile = context.getDatabasePath(databaseName)
        val journalFile = File(databaseFile.path + "-journal")
        val transactionEntered = CountDownLatch(1)
        val finishTransaction = CountDownLatch(1)
        val transactionFailure = AtomicReference<Throwable?>()
        val transactionThread = Thread({
            try {
                openedDatabase.runInTransaction {
                    openedDatabase.openHelper.writableDatabase.execSQL(
                        "UPDATE comfyui_jobs SET requestId = ? WHERE promptId = ?",
                        arrayOf(replacementRequestId, record.promptId)
                    )
                    transactionEntered.countDown()
                    check(finishTransaction.await(10, TimeUnit.SECONDS)) {
                        "Timed out while waiting for rollback-journal inspection"
                    }
                }
            } catch (failure: Throwable) {
                transactionFailure.set(failure)
                transactionEntered.countDown()
            }
        }, "sqlcipher-rollback-journal-test").apply {
            isDaemon = true
        }

        transactionThread.start()
        try {
            assertTrue(
                "The SQLCipher update transaction must remain open for journal inspection",
                transactionEntered.await(10, TimeUnit.SECONDS)
            )
            transactionFailure.get()?.let {
                throw IllegalStateException("The SQLCipher update transaction failed", it)
            }
            assertTrue(
                "SQLite must create a rollback journal while the update is uncommitted",
                journalFile.isFile
            )
            assertTrue(
                "The rollback journal must contain a page image while the update is uncommitted",
                journalFile.length() > 0L
            )

            val journalBytes = readBoundedBytes(journalFile, MAX_TEST_FILE_BYTES)
            assertFalse(
                "The SQLCipher rollback journal must not expose the previous request ID",
                containsBytes(journalBytes, originalRequestId.toByteArray(Charsets.UTF_8))
            )
            assertFalse(
                "The SQLCipher rollback journal must not expose the replacement request ID",
                containsBytes(journalBytes, replacementRequestId.toByteArray(Charsets.UTF_8))
            )
        } finally {
            finishTransaction.countDown()
            transactionThread.join(10_000)
        }

        assertFalse("The SQLCipher update transaction must finish", transactionThread.isAlive)
        transactionFailure.get()?.let {
            throw IllegalStateException("The SQLCipher update transaction failed", it)
        }
        assertEquals(replacementRequestId, store.get(record.promptId)?.requestId)

        openedDatabase.close()
        database = null

        val persistedDatabaseBytes = readBoundedBytes(databaseFile, MAX_TEST_FILE_BYTES)
        assertFalse(
            "The SQLCipher database file must not expose the previous request ID",
            containsBytes(persistedDatabaseBytes, originalRequestId.toByteArray(Charsets.UTF_8))
        )
        assertFalse(
            "The SQLCipher database file must not expose the replacement request ID",
            containsBytes(persistedDatabaseBytes, replacementRequestId.toByteArray(Charsets.UTF_8))
        )

        val reopenedDatabase = openDatabase(RoomDatabase.JournalMode.TRUNCATE)
        database = reopenedDatabase
        val reopenedStore = RoomComfyUiJobStore(reopenedDatabase.comfyUiJobDao())
        assertEquals(
            "The committed job correlation must remain readable after SQLCipher reopen",
            replacementRequestId,
            reopenedStore.get(record.promptId)?.requestId
        )
    }

    private fun openDatabase(journalMode: RoomDatabase.JournalMode): GalaxyDatabase {
        val keyBytes = Base64.encode(passphrase, Base64.NO_WRAP)
        encodedPassphrases += keyBytes
        val opened = Room.databaseBuilder(context, GalaxyDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(keyBytes, null, true))
            .setJournalMode(journalMode)
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

    private fun readBoundedBytes(file: File, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(4096)
            while (output.size() <= maxBytes) {
                val remaining = maxBytes + 1 - output.size()
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        }
        check(output.size() <= maxBytes) {
            "The test database sidecar exceeded the bounded inspection limit"
        }
        return output.toByteArray()
    }

    private fun containsBytes(container: ByteArray, candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > container.size) return false
        return (0..container.size - candidate.size).any { start ->
            candidate.indices.all { offset -> container[start + offset] == candidate[offset] }
        }
    }

    private companion object {
        const val MAX_TEST_FILE_BYTES = 1024 * 1024
    }
}
