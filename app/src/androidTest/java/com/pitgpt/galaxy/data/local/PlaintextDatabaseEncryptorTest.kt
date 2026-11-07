package com.pitgpt.galaxy.data.local

import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

@RunWith(AndroidJUnit4::class)
class PlaintextDatabaseEncryptorTest {
    private lateinit var directory: File
    private lateinit var databaseFile: File
    private lateinit var encryptor: PlaintextDatabaseEncryptor
    private val databaseKey = ByteArray(32).also(SecureRandom()::nextBytes)

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        directory = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir.resolve("database-encryption-tests").apply {
                mkdirs()
            }
        databaseFile = File(directory, "galaxy-test.db")
        encryptor = PlaintextDatabaseEncryptor()
        clearFiles()
    }

    @After
    fun tearDown() {
        databaseKey.fill(0)
        clearFiles()
    }

    @Test
    fun migrationEncryptsAllRowsAndPreservesSchemaMetadata() {
        createPlaintextDatabase()

        val pending = encryptor.migrateIfNeeded(databaseFile, databaseKey)

        assertNotNull(pending)
        assertFalse(encryptor.isPlaintextDatabase(databaseFile))
        assertTrue(File(directory, "galaxy-test.db.plaintext-backup").exists())
        openEncrypted(databaseFile, databaseKey).use { encrypted ->
            assertEquals(2, encrypted.version)
            assertEquals(0x47414C58, pragmaInt(encrypted, "application_id"))
            assertEquals(2L, countRows(encrypted, "character_profiles"))
            assertEquals(3L, countRows(encrypted, "conversation_messages"))
            assertEquals(2L, countRows(encrypted, "memory_items"))
            encrypted.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
        }

        // Simulate a process restart after encrypted-file promotion but before backup cleanup.
        val recovered = encryptor.migrateIfNeeded(databaseFile, databaseKey)
        assertNotNull(recovered)
        encryptor.complete(recovered)

        assertFalse(File(directory, "galaxy-test.db.plaintext-backup").exists())
        assertFalse(encryptor.isPlaintextDatabase(databaseFile))
    }

    @Test
    fun rollbackRestoresOriginalPlaintextDatabase() {
        createPlaintextDatabase()
        val pending = encryptor.migrateIfNeeded(databaseFile, databaseKey)
        assertNotNull(pending)

        encryptor.rollback(pending)

        assertTrue(encryptor.isPlaintextDatabase(databaseFile))
        assertFalse(File(directory, "galaxy-test.db.plaintext-backup").exists())
        FrameworkSQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READONLY
        ).use { restored ->
            assertEquals(2L, countFrameworkRows(restored, "character_profiles"))
            assertEquals(3L, countFrameworkRows(restored, "conversation_messages"))
            assertEquals(2L, countFrameworkRows(restored, "memory_items"))
            assertEquals(2, restored.version)
        }
    }

    @Test
    fun wrongKeyDoesNotDeleteOriginalPlaintextRollbackCopy() {
        createPlaintextDatabase()
        val pending = encryptor.migrateIfNeeded(databaseFile, databaseKey)
        assertNotNull(pending)

        val wrongKey = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            var failed = false
            try {
                encryptor.migrateIfNeeded(databaseFile, wrongKey)
            } catch (_: Exception) {
                failed = true
            }
            assertTrue(failed)
            assertTrue(File(directory, "galaxy-test.db.plaintext-backup").exists())
            assertFalse(encryptor.isPlaintextDatabase(databaseFile))
        } finally {
            wrongKey.fill(0)
        }
    }

    private fun createPlaintextDatabase() {
        FrameworkSQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL("CREATE TABLE character_profiles (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL)")
            db.execSQL("CREATE TABLE conversation_messages (id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL)")
            db.execSQL("CREATE TABLE memory_items (id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL)")
            db.execSQL("INSERT INTO character_profiles VALUES ('one', 'Galaxy')")
            db.execSQL("INSERT INTO character_profiles VALUES ('two', 'Nova')")
            db.execSQL("INSERT INTO conversation_messages VALUES ('m1', 'hello')")
            db.execSQL("INSERT INTO conversation_messages VALUES ('m2', 'world')")
            db.execSQL("INSERT INTO conversation_messages VALUES ('m3', 'persist')")
            db.execSQL("INSERT INTO memory_items VALUES ('r1', 'first memory')")
            db.execSQL("INSERT INTO memory_items VALUES ('r2', 'second memory')")
            db.version = 2
            db.execSQL("PRAGMA application_id = 1195461720")
        }
    }

    private fun openEncrypted(file: File, key: ByteArray): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            Base64.encodeToString(key, Base64.NO_WRAP),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null
        )

    private fun countRows(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun countFrameworkRows(db: FrameworkSQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun pragmaInt(db: SQLiteDatabase, pragma: String): Int =
        db.rawQuery("PRAGMA $pragma", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun clearFiles() {
        listOf(
            databaseFile,
            File(directory, "galaxy-test.db-wal"),
            File(directory, "galaxy-test.db-shm"),
            File(directory, "galaxy-test.db-journal"),
            File(directory, "galaxy-test.db.plaintext-backup"),
            File(directory, "galaxy-test.db.plaintext-backup-wal"),
            File(directory, "galaxy-test.db.plaintext-backup-shm"),
            File(directory, "galaxy-test.db.encrypted-tmp"),
            File(directory, "galaxy-test.db.encrypted-failed")
        ).forEach { it.delete() }
    }
}
