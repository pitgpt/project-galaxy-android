package com.pitgpt.galaxy.data.local

import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
    fun migrationPreservesTheActualRoomSchemaAndOpensThroughRoom() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val roomDatabase = androidx.room.Room.databaseBuilder(
            context,
            GalaxyDatabase::class.java,
            databaseFile.absolutePath
        ).build()

        roomDatabase.openHelper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO character_profiles (
                    id, displayName, description, personaDefinition, avatarId,
                    voiceId, modelProviderId, isBuiltIn, avatarImageUri
                ) VALUES (
                    'galaxy.default', 'Galaxy', 'Companion', 'Warm and curious',
                    'avatar.cosmic', 'voice.warm', 'provider.local', 1, 'content://avatar'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO conversation_messages (
                    id, characterId, role, content, createdAtEpochMillis
                ) VALUES ('message-1', 'galaxy.default', 'USER', 'Preserve this message', 123456789)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO memory_items (
                    id, scope, ownerCharacterId, content, importance,
                    createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES ('memory-1', 'CHARACTER', 'galaxy.default', 'Preserve this memory', 7, 111, 222)
                """.trimIndent()
            )
        }
        roomDatabase.close()

        val pending = encryptor.migrateIfNeeded(databaseFile, databaseKey)
        assertNotNull(pending)

        val passphrase = Base64.encodeToString(databaseKey, Base64.NO_WRAP)
        val encryptedRoom = androidx.room.Room.databaseBuilder(
            context,
            GalaxyDatabase::class.java,
            databaseFile.absolutePath
        )
            .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                passphrase.toByteArray(Charsets.UTF_8),
                null,
                true
            ))
            .build()

        try {
            val db = encryptedRoom.openHelper.writableDatabase
            db.query(
                "SELECT displayName, avatarImageUri FROM character_profiles WHERE id = ?",
                arrayOf("galaxy.default")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Galaxy", cursor.getString(0))
                assertEquals("content://avatar", cursor.getString(1))
            }
            db.query(
                "SELECT content, createdAtEpochMillis FROM conversation_messages WHERE id = ?",
                arrayOf("message-1")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Preserve this message", cursor.getString(0))
                assertEquals(123456789L, cursor.getLong(1))
            }
            db.query(
                "SELECT content, importance, ownerCharacterId FROM memory_items WHERE id = ?",
                arrayOf("memory-1")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Preserve this memory", cursor.getString(0))
                assertEquals(7, cursor.getInt(1))
                assertEquals("galaxy.default", cursor.getString(2))
            }
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
                arrayOf("index_conversation_messages_characterId_createdAtEpochMillis")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).isNotBlank())
            }
        } finally {
            encryptedRoom.close()
        }

        encryptor.complete(pending)
        assertFalse(File(directory, "galaxy-test.db.plaintext-backup").exists())
    }

    @Test
    fun recoversWhenProcessStopsAfterPreservingPlaintextButBeforeEncryptedPromotion() {
        createPlaintextDatabase()
        val pending = encryptor.migrateIfNeeded(databaseFile, databaseKey)
        assertNotNull(pending)

        // Recreate the on-disk state after the plaintext file was moved to its backup,
        // but before the encrypted temporary file was promoted to the canonical path.
        val encryptedTemporary = File(directory, "galaxy-test.db.encrypted-tmp")
        assertTrue(databaseFile.renameTo(encryptedTemporary))
        assertTrue(File(directory, "galaxy-test.db.plaintext-backup").exists())
        assertFalse(databaseFile.exists())

        val recovered = encryptor.migrateIfNeeded(databaseFile, databaseKey)

        assertNotNull(recovered)
        assertTrue(databaseFile.exists())
        assertFalse(encryptor.isPlaintextDatabase(databaseFile))
        openEncrypted(databaseFile, databaseKey).use { encrypted ->
            assertEquals(2L, countRows(encrypted, "character_profiles"))
            assertEquals(3L, countRows(encrypted, "conversation_messages"))
            assertEquals(2L, countRows(encrypted, "memory_items"))
        }
        encryptor.complete(recovered)
        assertFalse(File(directory, "galaxy-test.db.plaintext-backup").exists())
    }

    @Test
    fun restoresWalAndShmSidecarsMovedBeforePlaintextDatabasePromotion() {
        createPlaintextDatabase()
        val walDatabase = FrameworkSQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READWRITE
        )

        val pending = try {
            assertTrue(walDatabase.enableWriteAheadLogging())
            walDatabase.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
            walDatabase.execSQL("INSERT INTO character_profiles VALUES ('wal-only', 'WAL record')")

            val wal = File(databaseFile.path + "-wal")
            val shm = File(databaseFile.path + "-shm")
            assertTrue(wal.exists() && wal.length() > 0)
            assertTrue(shm.exists() && shm.length() > 0)

            // Simulate process death after both sidecars were moved, but before the
            // plaintext database itself was renamed to its rollback backup.
            val backup = File(directory, "galaxy-test.db.plaintext-backup")
            assertTrue(wal.renameTo(File(backup.path + "-wal")))
            assertTrue(shm.renameTo(File(backup.path + "-shm")))
            assertFalse(wal.exists())
            assertFalse(shm.exists())

            encryptor.migrateIfNeeded(databaseFile, databaseKey)
        } finally {
            walDatabase.close()
        }

        assertNotNull(pending)
        assertFalse(encryptor.isPlaintextDatabase(databaseFile))
        openEncrypted(databaseFile, databaseKey).use { encrypted ->
            assertEquals(3L, countRows(encrypted, "character_profiles"))
            assertEquals(3L, countRows(encrypted, "conversation_messages"))
            assertEquals(2L, countRows(encrypted, "memory_items"))
        }

        encryptor.complete(pending)
        assertFalse(File(directory, "galaxy-test.db.plaintext-backup").exists())
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
    fun migrationMovesPersistentRollbackJournalWithPlaintextBackupAndCompleteRemovesIt() {
        createPlaintextDatabase()
        val journalBytes = createPersistentRollbackJournal(databaseFile)
        val journal = File(databaseFile.path + "-journal")

        val pending = checkNotNull(encryptor.migrateIfNeeded(databaseFile, databaseKey))
        val backup = File(directory, "galaxy-test.db.plaintext-backup")
        val backupJournal = File(backup.path + "-journal")

        assertFalse("The journal must move away from the encrypted database path", journal.exists())
        assertArrayEquals("The persistent journal must remain byte-for-byte attached to the plaintext backup", journalBytes, backupJournal.readBytes())
        openEncrypted(databaseFile, databaseKey).use { encrypted ->
            assertEquals(3L, countRows(encrypted, "memory_items"))
        }

        encryptor.complete(pending)

        assertFalse("Completing migration must remove the plaintext journal sidecar", backupJournal.exists())
    }

    @Test
    fun restoresMovedPersistentJournalBeforeContinuingMigration() {
        createPlaintextDatabase()
        val journalBytes = createPersistentRollbackJournal(databaseFile)
        val journal = File(databaseFile.path + "-journal")
        val backupJournal = File(directory, "galaxy-test.db.plaintext-backup-journal")
        assertTrue(journal.renameTo(backupJournal))

        val temporaryDirectory = File(directory, "galaxy-test.db.encrypted-tmp")
        assertTrue(temporaryDirectory.mkdirs())
        File(temporaryDirectory, "keep").writeText("block deletion until sidecar recovery is observed")
        try {
            var migrationFailure: IllegalStateException? = null
            try {
                encryptor.migrateIfNeeded(databaseFile, databaseKey)
            } catch (failure: IllegalStateException) {
                migrationFailure = failure
            }

            assertNotNull("The non-empty temporary directory must stop migration after recovery", migrationFailure)
            assertTrue(databaseFile.exists())
            assertTrue(encryptor.isPlaintextDatabase(databaseFile))
            assertArrayEquals("The journal must be restored to the canonical plaintext database path", journalBytes, journal.readBytes())
            assertFalse("The backup-named journal must no longer be orphaned", backupJournal.exists())
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    @Test
    fun rollbackRestoresPersistentPlaintextJournalAndRemovesEncryptedJournalSidecar() {
        createPlaintextDatabase()
        val journalBytes = createPersistentRollbackJournal(databaseFile)
        val pending = checkNotNull(encryptor.migrateIfNeeded(databaseFile, databaseKey))
        val backup = File(directory, "galaxy-test.db.plaintext-backup")
        val backupJournal = File(backup.path + "-journal")
        assertArrayEquals(journalBytes, backupJournal.readBytes())

        val encryptedJournal = File(databaseFile.path + "-journal")
        val encryptedJournalBytes = byteArrayOf(0x31, 0x42, 0x53, 0x64)
        encryptedJournal.writeBytes(encryptedJournalBytes)

        encryptor.rollback(pending)

        assertTrue(encryptor.isPlaintextDatabase(databaseFile))
        assertFalse(backup.exists())
        assertArrayEquals("The plaintext journal must be restored beside its database", journalBytes, encryptedJournal.readBytes())
        assertFalse(File(directory, "galaxy-test.db.encrypted-failed").exists())
        assertFalse(File(directory, "galaxy-test.db.encrypted-failed-journal").exists())

        FrameworkSQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READONLY
        ).use { restored ->
            assertEquals(3L, countFrameworkRows(restored, "memory_items"))
            restored.rawQuery("SELECT content FROM memory_items WHERE id = 'journal-record'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Persistent rollback journal row", cursor.getString(0))
            }
        }
    }

    @Test
    fun removesOrphanRollbackJournalWhenEncryptedDatabaseHasNoPlaintextBackup() {
        createPlaintextDatabase()
        val pending = checkNotNull(encryptor.migrateIfNeeded(databaseFile, databaseKey))
        encryptor.complete(pending)

        val backup = File(directory, "galaxy-test.db.plaintext-backup")
        assertFalse("The plaintext backup must be absent before orphan sidecars are created", backup.exists())
        val orphanWal = File(backup.path + "-wal")
        val orphanShm = File(backup.path + "-shm")
        val orphanJournal = File(backup.path + "-journal")
        orphanWal.writeBytes(byteArrayOf(0x11, 0x22, 0x33))
        orphanShm.writeBytes(byteArrayOf(0x44, 0x55, 0x66))
        orphanJournal.writeBytes(byteArrayOf(0x77, 0x66, 0x55, 0x44))

        val recovered = encryptor.migrateIfNeeded(databaseFile, databaseKey)

        assertEquals("An already encrypted database without a plaintext backup needs no pending migration", null, recovered)
        assertFalse("The orphan WAL must be removed", orphanWal.exists())
        assertFalse("The orphan SHM must be removed", orphanShm.exists())
        assertFalse("The orphan plaintext rollback journal must be removed", orphanJournal.exists())
        assertFalse(encryptor.isPlaintextDatabase(databaseFile))
        openEncrypted(databaseFile, databaseKey).use { encrypted ->
            assertEquals(2L, countRows(encrypted, "character_profiles"))
            assertEquals(3L, countRows(encrypted, "conversation_messages"))
            assertEquals(2L, countRows(encrypted, "memory_items"))
        }
    }

    @Test
    fun rollbackRefusesToOverwriteExistingFailedEncryptedCandidate() {
        createPlaintextDatabase()
        val pending = checkNotNull(encryptor.migrateIfNeeded(databaseFile, databaseKey))

        val backup = File(directory, "galaxy-test.db.plaintext-backup")
        val failedEncrypted = File(directory, "galaxy-test.db.encrypted-failed")
        SQLiteDatabase.openDatabase(
            failedEncrypted.absolutePath,
            Base64.encodeToString(databaseKey, Base64.NO_WRAP),
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null
        ).use { candidate ->
            candidate.execSQL("CREATE TABLE retained_marker (id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            candidate.execSQL("INSERT INTO retained_marker VALUES ('previous', 'keep this encrypted candidate')")
        }

        val failedCandidateBytes = failedEncrypted.readBytes()
        val activeEncryptedBytes = databaseFile.readBytes()
        val plaintextBackupBytes = backup.readBytes()

        var rollbackFailure: IllegalStateException? = null
        try {
            encryptor.rollback(pending)
        } catch (failure: IllegalStateException) {
            rollbackFailure = failure
        }

        assertNotNull("Rollback must fail closed when a previous encrypted candidate exists", rollbackFailure)
        assertArrayEquals(failedCandidateBytes, failedEncrypted.readBytes())
        assertArrayEquals(activeEncryptedBytes, databaseFile.readBytes())
        assertArrayEquals(plaintextBackupBytes, backup.readBytes())

        openEncrypted(failedEncrypted, databaseKey).use { candidate ->
            assertEquals(1L, countRows(candidate, "retained_marker"))
            candidate.rawQuery("SELECT value FROM retained_marker WHERE id = 'previous'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep this encrypted candidate", cursor.getString(0))
            }
        }
        openEncrypted(databaseFile, databaseKey).use { active ->
            assertEquals(2L, countRows(active, "character_profiles"))
            assertEquals(3L, countRows(active, "conversation_messages"))
            assertEquals(2L, countRows(active, "memory_items"))
        }
        FrameworkSQLiteDatabase.openDatabase(
            backup.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READONLY
        ).use { restored ->
            assertEquals(2L, countFrameworkRows(restored, "character_profiles"))
            assertEquals(3L, countFrameworkRows(restored, "conversation_messages"))
            assertEquals(2L, countFrameworkRows(restored, "memory_items"))
        }
    }

    @Test
    fun rollbackRefusesToOverwriteOrphanFailedSidecars() {
        createPlaintextDatabase()
        val pending = checkNotNull(encryptor.migrateIfNeeded(databaseFile, databaseKey))

        val backup = File(directory, "galaxy-test.db.plaintext-backup")
        val failedEncrypted = File(directory, "galaxy-test.db.encrypted-failed")
        val failedWal = File(failedEncrypted.path + "-wal")
        val failedShm = File(failedEncrypted.path + "-shm")
        val failedJournal = File(failedEncrypted.path + "-journal")
        val walBytes = byteArrayOf(0x11, 0x22, 0x33)
        val shmBytes = byteArrayOf(0x44, 0x55, 0x66)
        val journalBytes = byteArrayOf(0x77, 0x66, 0x55, 0x44)
        failedWal.writeBytes(walBytes)
        failedShm.writeBytes(shmBytes)
        failedJournal.writeBytes(journalBytes)

        val activeEncryptedBytes = databaseFile.readBytes()
        val plaintextBackupBytes = backup.readBytes()

        var rollbackFailure: IllegalStateException? = null
        try {
            encryptor.rollback(pending)
        } catch (failure: IllegalStateException) {
            rollbackFailure = failure
        }

        assertNotNull("Rollback must fail closed when orphan failed-candidate sidecars exist", rollbackFailure)
        assertArrayEquals(walBytes, failedWal.readBytes())
        assertArrayEquals(shmBytes, failedShm.readBytes())
        assertArrayEquals(journalBytes, failedJournal.readBytes())
        assertArrayEquals(activeEncryptedBytes, databaseFile.readBytes())
        assertArrayEquals(plaintextBackupBytes, backup.readBytes())
        assertTrue(failedWal.exists())
        assertTrue(failedShm.exists())
        assertTrue(failedJournal.exists())
    }

    @Test
    fun rollbackRefusesWhenPlaintextBackupNoLongerMatchesVerifiedSnapshot() {
        createPlaintextDatabase()
        val pending = encryptor.migrateIfNeeded(databaseFile, databaseKey)
        assertNotNull(pending)

        val backup = File(directory, "galaxy-test.db.plaintext-backup")
        FrameworkSQLiteDatabase.openDatabase(
            backup.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READWRITE
        ).use { plaintextBackup ->
            plaintextBackup.execSQL("DELETE FROM memory_items WHERE id = 'r2'")
        }

        val activeEncryptedBytes = databaseFile.readBytes()
        val failedEncrypted = File(directory, "galaxy-test.db.encrypted-failed")
        var rollbackFailure: IllegalStateException? = null
        try {
            encryptor.rollback(pending)
        } catch (failure: IllegalStateException) {
            rollbackFailure = failure
        }

        assertNotNull(
            "Rollback must refuse a plaintext backup that no longer matches the verified snapshot",
            rollbackFailure
        )
        assertTrue(rollbackFailure?.message?.contains("verified migration snapshot") == true)
        assertArrayEquals(activeEncryptedBytes, databaseFile.readBytes())
        assertTrue(backup.isFile)
        assertFalse(failedEncrypted.exists())

        openEncrypted(databaseFile, databaseKey).use { active ->
            assertEquals(2L, countRows(active, "character_profiles"))
            assertEquals(3L, countRows(active, "conversation_messages"))
            assertEquals(2L, countRows(active, "memory_items"))
        }
        FrameworkSQLiteDatabase.openDatabase(
            backup.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READONLY
        ).use { modifiedBackup ->
            assertEquals(1L, countFrameworkRows(modifiedBackup, "memory_items"))
        }
    }

    @Test
    fun rollbackRefusesWhenPlaintextBackupIsEmptyBeforeMovingEncryptedCandidate() {
        createPlaintextDatabase()
        val pending = encryptor.migrateIfNeeded(databaseFile, databaseKey)
        assertNotNull(pending)

        val backup = File(directory, "galaxy-test.db.plaintext-backup")
        backup.writeBytes(byteArrayOf())
        val activeEncryptedBytes = databaseFile.readBytes()
        val failedEncrypted = File(directory, "galaxy-test.db.encrypted-failed")

        var rollbackFailure: IllegalStateException? = null
        try {
            encryptor.rollback(pending)
        } catch (failure: IllegalStateException) {
            rollbackFailure = failure
        }

        assertNotNull("Rollback must refuse an empty plaintext backup", rollbackFailure)
        assertTrue(rollbackFailure?.message?.contains("missing or empty") == true)
        assertArrayEquals(activeEncryptedBytes, databaseFile.readBytes())
        assertTrue(backup.isFile)
        assertEquals(0L, backup.length())
        assertFalse(failedEncrypted.exists())
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

    private fun createPersistentRollbackJournal(file: File): ByteArray {
        FrameworkSQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.rawQuery("PRAGMA journal_mode=PERSIST", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("persist", cursor.getString(0).lowercase())
            }
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT INTO memory_items VALUES ('journal-record', 'Persistent rollback journal row')"
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        val journal = File(file.path + "-journal")
        assertTrue("PERSIST mode must leave a rollback journal file", journal.isFile && journal.length() > 0L)
        return journal.readBytes()
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
            File(directory, "galaxy-test.db.plaintext-backup-journal"),
            File(directory, "galaxy-test.db.encrypted-tmp"),
            File(directory, "galaxy-test.db.encrypted-failed"),
            File(directory, "galaxy-test.db.encrypted-failed-wal"),
            File(directory, "galaxy-test.db.encrypted-failed-shm"),
            File(directory, "galaxy-test.db.encrypted-failed-journal")
        ).forEach { it.delete() }
    }
}
