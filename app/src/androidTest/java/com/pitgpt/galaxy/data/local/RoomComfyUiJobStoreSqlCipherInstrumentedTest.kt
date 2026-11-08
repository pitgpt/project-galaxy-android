package com.pitgpt.galaxy.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.RoomComfyUiJobStore
import java.io.File
import java.io.FileInputStream
import java.security.SecureRandom
import java.util.UUID
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
    private lateinit var database: GalaxyDatabase
    private lateinit var passphrase: ByteArray
    private lateinit var passphraseBytes: ByteArray

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "comfyui-job-store-test-${UUID.randomUUID()}.db"
        passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        passphraseBytes = Base64.encode(passphrase, Base64.NO_WRAP)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        val file = context.getDatabasePath(databaseName)
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        passphrase.fill(0)
        passphraseBytes.fill(0)
    }

    @Test
    fun jobCorrelationSurvivesRoomReopenAndDatabaseFileIsSqlCipherEncrypted() {
        val record = ComfyUiJobRecord(
            promptId = "prompt-${UUID.randomUUID()}",
            requestId = "request-${UUID.randomUUID()}",
            createdAtEpochMillis = 123456L
        )
        val store = RoomComfyUiJobStore(database.comfyUiJobDao())

        kotlinx.coroutines.runBlocking {
            store.put(record)
            store.markCancelled(record.promptId)
        }
        database.close()

        val databaseFile = context.getDatabasePath(databaseName)
        assertTrue(databaseFile.isFile)
        val header = ByteArray(16)
        FileInputStream(databaseFile).use { input ->
            assertEquals(16, input.read(header))
        }
        assertFalse(
            "SQLCipher database must not expose the plaintext SQLite file header",
            String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
        )

        database = openDatabase()
        val reopenedStore = RoomComfyUiJobStore(database.comfyUiJobDao())
        kotlinx.coroutines.runBlocking {
            assertEquals(record.copy(cancelled = true), reopenedStore.get(record.promptId))
        }
    }

    private fun openDatabase(): GalaxyDatabase =
        Room.databaseBuilder(context, GalaxyDatabase::class.java, databaseName)
            .openHelperFactory(SupportOpenHelperFactory(passphraseBytes, null, true))
            .build()
}
