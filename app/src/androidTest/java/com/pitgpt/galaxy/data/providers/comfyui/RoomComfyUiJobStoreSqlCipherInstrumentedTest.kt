package com.pitgpt.galaxy.data.providers.comfyui

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import java.io.File
import java.io.FileInputStream
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
    private val encodedPassphrases = mutableListOf<ByteArray>()
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
            deleteDatabaseFiles(databaseName)
        }
        encodedPassphrases.forEach { it.fill(0) }
        encodedPassphrases.clear()
        if (::passphrase.isInitialized) passphrase.fill(0)
    }

    @Test
    fun jobCorrelationAndCancellationSurviveSqlCipherDatabaseReopen() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-durable-123",
            requestId = "galaxy-request-durable-456",
            createdAtEpochMillis = 1_794_230_400_000L
        )

        val firstDatabase = openDatabase()
        database = firstDatabase
        val firstStore = RoomComfyUiJobStore(firstDatabase.comfyUiJobDao())
        firstStore.put(record)
        firstStore.markCancelled(record.promptId)
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

        val reopenedDatabase = openDatabase()
        database = reopenedDatabase
        val reopenedStore = RoomComfyUiJobStore(reopenedDatabase.comfyUiJobDao())

        assertEquals(record.copy(cancelled = true), reopenedStore.get(record.promptId))
    }

    @Test
    fun committedWalFramesRecoverWithoutTheSharedMemorySidecar() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-wal-recovery-123",
            requestId = "galaxy-request-wal-recovery-456",
            createdAtEpochMillis = 1_794_230_400_000L
        )

        val firstDatabase = openDatabase()
        database = firstDatabase
        val sqlDatabase = firstDatabase.openHelper.writableDatabase
        val journalMode = sqlDatabase.query("PRAGMA journal_mode").use { cursor ->
            assertTrue("The SQLCipher helper must expose a journal mode", cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("The recovery scenario requires WAL mode", "wal", journalMode.lowercase())

        RoomComfyUiJobStore(firstDatabase.comfyUiJobDao()).put(record)

        val sourceFile = context.getDatabasePath(databaseName)
        val sourceWal = File(sourceFile.path + "-wal")
        assertTrue("A committed WAL must exist before the database is closed", sourceWal.isFile)
        assertTrue("The WAL must contain at least one committed frame", sourceWal.length() > 32L)
        assertFalse(
            "SQLCipher WAL frames must not expose persisted request identifiers",
            containsBytes(sourceWal.readBytes(), record.requestId.toByteArray(Charsets.UTF_8))
        )

        // A process restart retains the database and WAL, but SQLite rebuilds the transient
        // shared-memory index. Copy that durable file set without the -shm sidecar.
        val snapshotName = "$databaseName-crash-snapshot"
        val snapshotFile = context.getDatabasePath(snapshotName)
        val snapshotWal = File(snapshotFile.path + "-wal")
        sourceFile.copyTo(snapshotFile, overwrite = true)
        sourceWal.copyTo(snapshotWal, overwrite = true)
        assertFalse(
            "The crash snapshot must not contain SQLite's transient shared-memory sidecar",
            File(snapshotFile.path + "-shm").exists()
        )

        var snapshotDatabase: GalaxyDatabase? = null
        try {
            snapshotDatabase = openDatabase(snapshotName)
            val recoveredStore = RoomComfyUiJobStore(snapshotDatabase.comfyUiJobDao())
            assertEquals(
                "Committed SQLCipher WAL frames must recover the durable ComfyUI job",
                record,
                recoveredStore.get(record.promptId)
            )
        } finally {
            runCatching { snapshotDatabase?.close() }
            deleteDatabaseFiles(snapshotName)
        }
    }

    @Test
    fun wrongPassphraseCannotReplaceExistingSqlCipherJobDatabase() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-wrong-key-123",
            requestId = "galaxy-request-wrong-key-456",
            createdAtEpochMillis = 1_794_230_400_000L
        )

        val firstDatabase = openDatabase()
        database = firstDatabase
        RoomComfyUiJobStore(firstDatabase.comfyUiJobDao()).put(record)
        firstDatabase.close()
        database = null

        val wrongPassphrase = ByteArray(32) { (it + 1).toByte() }
        var unexpectedDatabase: GalaxyDatabase? = null
        val wrongKeyOpenFailure = try {
            unexpectedDatabase = openDatabase(wrongPassphrase)
            null
        } catch (failure: Exception) {
            failure
        } finally {
            runCatching { unexpectedDatabase?.close() }
            wrongPassphrase.fill(0)
        }

        assertTrue(
            "An existing SQLCipher database must reject a different passphrase",
            wrongKeyOpenFailure != null
        )

        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue("The original encrypted database must remain present", databaseFile.isFile)
        assertTrue("The original encrypted database must remain non-empty", databaseFile.length() > 0)

        val reopenedDatabase = openDatabase()
        database = reopenedDatabase
        val reopenedStore = RoomComfyUiJobStore(reopenedDatabase.comfyUiJobDao())

        assertEquals(
            "A rejected wrong-key attempt must not destroy the previously stored job",
            record,
            reopenedStore.get(record.promptId)
        )
    }

    @Test
    fun duplicatePromptIdIsRejectedWithoutOverwritingPersistedJob() = runBlocking {
        val original = ComfyUiJobRecord(
            promptId = "prompt-duplicate-123",
            requestId = "galaxy-request-original-456",
            createdAtEpochMillis = 1_794_230_400_000L
        )

        val firstDatabase = openDatabase()
        database = firstDatabase
        val firstStore = RoomComfyUiJobStore(firstDatabase.comfyUiJobDao())
        firstStore.put(original)

        val duplicateFailure = runCatching {
            firstStore.put(
                original.copy(
                    requestId = "galaxy-request-replacement-789",
                    createdAtEpochMillis = original.createdAtEpochMillis + 1
                )
            )
        }.exceptionOrNull()

        assertTrue(
            "A duplicate prompt ID must be rejected by the SQLCipher-backed primary key",
            duplicateFailure != null
        )
        assertEquals(original, firstStore.get(original.promptId))

        firstDatabase.close()
        database = null

        val reopenedDatabase = openDatabase()
        database = reopenedDatabase
        val reopenedStore = RoomComfyUiJobStore(reopenedDatabase.comfyUiJobDao())

        assertEquals(
            "A rejected duplicate must not replace the original durable request correlation",
            original,
            reopenedStore.get(original.promptId)
        )
    }

    private fun openDatabase(rawPassphrase: ByteArray = passphrase): GalaxyDatabase =
        openDatabase(databaseName, rawPassphrase)

    private fun openDatabase(name: String, rawPassphrase: ByteArray = passphrase): GalaxyDatabase {
        val keyBytes = Base64.encode(rawPassphrase, Base64.NO_WRAP)
        encodedPassphrases += keyBytes
        val opened = Room.databaseBuilder(context, GalaxyDatabase::class.java, name)
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

    private fun deleteDatabaseFiles(name: String) {
        val file = context.getDatabasePath(name)
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }

    private fun containsBytes(container: ByteArray, candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > container.size) return false
        return (0..container.size - candidate.size).any { start ->
            candidate.indices.all { offset -> container[start + offset] == candidate[offset] }
        }
    }
}
