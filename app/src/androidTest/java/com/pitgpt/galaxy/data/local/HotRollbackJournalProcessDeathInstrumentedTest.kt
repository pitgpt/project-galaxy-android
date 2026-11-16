package com.pitgpt.galaxy.data.local

import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.os.Process
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertArrayEquals
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
class HotRollbackJournalProcessDeathInstrumentedTest {
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun aLeaveHotRollbackJournalForOrchestratorProcessRestart() {
        cleanupFixture()
        createCommittedDatabase()

        val database = FrameworkSQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READWRITE
        )
        try {
            database.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("delete", cursor.getString(0).lowercase())
            }
            database.execSQL("PRAGMA synchronous=FULL")
            database.execSQL("PRAGMA cache_size=1")
            database.execSQL("PRAGMA cache_spill=ON")
            database.beginTransaction()

            // Force dirty pages out of the one-page cache while the transaction remains
            // uncommitted. SQLite must write the original page images and a valid journal
            // header before spilling database pages to disk.
            val payload = "x".repeat(8 * 1024)
            repeat(64) { index ->
                database.execSQL(
                    "INSERT INTO crash_records (id, value) VALUES (?, ?)",
                    arrayOf("uncommitted-$index", payload)
                )
            }

            val journal = rollbackJournal
            assertTrue("An uncommitted DELETE-mode transaction must create a rollback journal", journal.isFile)
            val journalBytes = journal.readBytes()
            assertTrue("The rollback journal must contain a header and page image", journalBytes.size > 512)
            assertArrayEquals(
                "The journal must have SQLite's rollback-journal magic header",
                ROLLBACK_JOURNAL_MAGIC,
                journalBytes.copyOfRange(0, ROLLBACK_JOURNAL_MAGIC.size)
            )

            // Keep the transaction and database handle open. Android Test Orchestrator
            // must terminate this target process before running the next test method.
            HotJournalDatabaseHolder.database = database
            FileOutputStream(processMarker).use { output ->
                output.write(Process.myPid().toString().toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
        } catch (failure: Throwable) {
            runCatching { database.close() }
            throw failure
        }
    }

    @Test
    fun bRecoverHotRollbackJournalBeforeEncryptingDatabase() {
        assertTrue("The first orchestrated test must leave a process marker", processMarker.isFile)
        val previousPid = processMarker.readText(Charsets.US_ASCII).trim().toInt()
        assertNotEquals(
            "Android Test Orchestrator must restart the target app process between test methods",
            previousPid,
            Process.myPid()
        )

        val journalBytes = rollbackJournal.readBytes()
        assertTrue("The hot rollback journal must survive process termination", journalBytes.size > 512)
        assertArrayEquals(
            "The uncommitted transaction must leave a valid hot-journal header",
            ROLLBACK_JOURNAL_MAGIC,
            journalBytes.copyOfRange(0, ROLLBACK_JOURNAL_MAGIC.size)
        )

        val key = testDatabaseKey()
        val encryptor = PlaintextDatabaseEncryptor()
        try {
            val pending = checkNotNull(encryptor.migrateIfNeeded(databaseFile, key)) {
                "The plaintext database must be encrypted after SQLite recovers its hot journal."
            }
            assertFalse(
                "Opening the plaintext database for migration must recover and remove its DELETE-mode hot journal",
                rollbackJournal.exists()
            )

            val backup = File(databaseFile.parentFile, "$DATABASE_NAME.plaintext-backup")
            openEncrypted(databaseFile, key).use { encrypted ->
                assertEquals(1L, countRows(encrypted))
                assertEquals("committed value", valueForId(encrypted, "committed"))
                assertEquals(null, valueForId(encrypted, "uncommitted-0"))
                encrypted.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0).lowercase())
                }
            }

            FrameworkSQLiteDatabase.openDatabase(
                backup.absolutePath,
                null,
                FrameworkSQLiteDatabase.OPEN_READONLY
            ).use { restored ->
                assertEquals(1L, countRows(restored))
                assertEquals("committed value", valueForId(restored, "committed"))
                assertEquals(null, valueForId(restored, "uncommitted-0"))
            }

            encryptor.complete(pending)
            assertFalse("The plaintext rollback backup must be removed only after verification", backup.exists())
        } finally {
            key.fill(0)
            cleanupFixture()
        }
    }

    private fun createCommittedDatabase() {
        databaseFile.parentFile?.mkdirs()
        FrameworkSQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("delete", cursor.getString(0).lowercase())
            }
            database.execSQL(
                "CREATE TABLE crash_records (id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)"
            )
            database.execSQL(
                "INSERT INTO crash_records (id, value) VALUES ('committed', 'committed value')"
            )
        }
    }

    private fun openEncrypted(file: File, key: ByteArray): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            Base64.encodeToString(key, Base64.NO_WRAP),
            null,
            SQLiteDatabase.OPEN_READONLY,
            null
        )

    private fun countRows(database: SQLiteDatabase): Long =
        database.rawQuery("SELECT COUNT(*) FROM crash_records", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun countRows(database: FrameworkSQLiteDatabase): Long =
        database.rawQuery("SELECT COUNT(*) FROM crash_records", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun valueForId(database: SQLiteDatabase, id: String): String? =
        database.rawQuery(
            "SELECT value FROM crash_records WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun valueForId(database: FrameworkSQLiteDatabase, id: String): String? =
        database.rawQuery(
            "SELECT value FROM crash_records WHERE id = ?",
            arrayOf(id)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun testDatabaseKey(): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("galaxy-hot-rollback-journal-process-death-test-v1".toByteArray(Charsets.UTF_8))

    private val databaseFile: File
        get() = context.getDatabasePath(DATABASE_NAME)

    private val rollbackJournal: File
        get() = File(databaseFile.path + "-journal")

    private val processMarker: File
        get() = File(context.noBackupFilesDir, MARKER_NAME)

    private fun cleanupFixture() {
        runCatching { HotJournalDatabaseHolder.database?.close() }
        HotJournalDatabaseHolder.database = null
        val backup = File(databaseFile.parentFile, "$DATABASE_NAME.plaintext-backup")
        val encryptedTemporary = File(databaseFile.parentFile, "$DATABASE_NAME.encrypted-tmp")
        val failedEncrypted = File(databaseFile.parentFile, "$DATABASE_NAME.encrypted-failed")
        listOf(
            databaseFile,
            rollbackJournal,
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
            backup,
            File(backup.path + "-journal"),
            File(backup.path + "-wal"),
            File(backup.path + "-shm"),
            encryptedTemporary,
            failedEncrypted,
            File(failedEncrypted.path + "-journal"),
            File(failedEncrypted.path + "-wal"),
            File(failedEncrypted.path + "-shm")
        ).forEach { it.delete() }
        processMarker.delete()
    }

    private companion object {
        const val DATABASE_NAME = "galaxy-hot-rollback-journal-process-death.db"
        const val MARKER_NAME = "galaxy-hot-rollback-journal-process-death.pid"
        val ROLLBACK_JOURNAL_MAGIC = byteArrayOf(
            0xD9.toByte(), 0xD5.toByte(), 0x05, 0xF9.toByte(),
            0x20, 0xA1.toByte(), 0x63, 0xD7.toByte()
        )
    }
}

private object HotJournalDatabaseHolder {
    var database: FrameworkSQLiteDatabase? = null
}
