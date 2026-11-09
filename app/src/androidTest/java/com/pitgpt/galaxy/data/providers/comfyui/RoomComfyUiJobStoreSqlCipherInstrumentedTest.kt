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
    fun preparedAndStartedSubmissionStateSurvivesSqlCipherDatabaseReopen() = runBlocking {
        val record = ComfyUiJobRecord(
            promptId = "prompt-durable-123",
            requestId = "galaxy-request-durable-456",
            createdAtEpochMillis = 1_794_230_400_000L
        )

        val firstDatabase = openDatabase()
        database = firstDatabase
        val firstStore = RoomComfyUiJobStore(firstDatabase.comfyUiJobDao())
        firstStore.put(record)
        assertTrue(firstStore.markSubmissionStarted(record.promptId))
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
        val expected = record.copy(cancelled = true, submissionStarted = true)

        assertEquals(expected, reopenedStore.get(record.promptId))
        assertEquals(listOf(expected), reopenedStore.all())
    }

    private fun openDatabase(): GalaxyDatabase {
        val keyBytes = Base64.encode(passphrase, Base64.NO_WRAP)
        val opened = Room.databaseBuilder(context, GalaxyDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(keyBytes, null, true))
            .build()
        try {
            opened.openHelper.writableDatabase
            keyBytes.fill(0)
            return opened
        } catch (failure: Exception) {
            keyBytes.fill(0)
            runCatching { opened.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}
