package com.pitgpt.galaxy.data.local

import android.content.Context
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class ComfyUiJobStoreMigrationSqlCipherInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var passphrase: ByteArray
    private lateinit var passphraseBytes: ByteArray

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "comfyui-job-migration-${UUID.randomUUID()}.db"
        passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        passphraseBytes = Base64.encode(passphrase, Base64.NO_WRAP)
        helper = SupportOpenHelperFactory(passphraseBytes, null, true).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE legacy_probe (id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO legacy_probe (id, value) VALUES ('before', 'preserved')"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
    }

    @After
    fun tearDown() {
        runCatching { helper.close() }
        val file = context.getDatabasePath(databaseName)
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        passphrase.fill(0)
        passphraseBytes.fill(0)
    }

    @Test
    fun versionTwoMigrationCreatesIndexedJobTableInsideSqlCipherDatabase() {
        val db = helper.writableDatabase
        assertEquals(2, db.version)

        GalaxyDatabaseFactory.MIGRATION_2_3.migrate(db)

        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(comfyui_jobs)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        assertEquals(
            setOf("promptId", "requestId", "createdAtEpochMillis", "cancelled"),
            columns
        )

        val indexes = mutableSetOf<String>()
        db.query("PRAGMA index_list(comfyui_jobs)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) indexes += cursor.getString(nameIndex)
        }
        assertTrue(indexes.contains("index_comfyui_jobs_requestId"))

        db.query("SELECT value FROM legacy_probe WHERE id = ?", arrayOf("before")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("preserved", cursor.getString(0))
        }

        db.execSQL(
            "INSERT INTO comfyui_jobs (promptId, requestId, createdAtEpochMillis, cancelled) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("prompt-1", "request-1", 1234L, 0)
        )
        db.query(
            "SELECT requestId, cancelled FROM comfyui_jobs WHERE promptId = ?",
            arrayOf("prompt-1")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("request-1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }

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
    }
}
