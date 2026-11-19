package com.pitgpt.galaxy.data.local

import android.content.Context
import android.os.Process
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Exercises the migration crash window after encrypted promotion and before completion.
 *
 * Android Test Orchestrator runs the two ordered methods in separate target processes. The first
 * method deliberately leaves the encrypted canonical DB and verified plaintext rollback copy
 * in place. The second method reconstructs PendingMigration from disk, opens the encrypted Room
 * database, and only then completes the migration.
 *
 * Every database, preference file, Keystore alias and marker is test-only. The production
 * galaxy.db and production database-key alias are never accessed.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SqlCipherMigrationProcessDeathRecoveryInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val databaseFile: File
        get() = context.getDatabasePath(DATABASE_NAME)

    private val plaintextBackup: File
        get() = File(databaseFile.parentFile, "${databaseFile.name}.plaintext-backup")

    private val encryptedTemporary: File
        get() = File(databaseFile.parentFile, "${databaseFile.name}.encrypted-tmp")

    private val markerFile: File
        get() = File(context.noBackupFilesDir, MARKER_NAME)

    @Test
    fun aPromoteEncryptedDatabaseAndLeaveMigrationPendingBeforeProcessDeath() {
        System.loadLibrary("sqlcipher")
        cleanupFixture()

        val keyStore = newKeyStore()
        val encryptor = PlaintextDatabaseEncryptor()
        var plaintextRoom: GalaxyDatabase? = null
        var databaseKey: ByteArray? = null
        var preparedForRestart = false

        try {
            plaintextRoom = Room.databaseBuilder(
                context,
                GalaxyDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

            val plaintextDatabase = plaintextRoom.openHelper.writableDatabase
            insertSyntheticRows(plaintextDatabase)
            plaintextRoom.close()
            plaintextRoom = null

            assertTrue(
                "The isolated fixture must start as a plaintext SQLite Room database",
                encryptor.isPlaintextDatabase(databaseFile)
            )

            databaseKey = keyStore.getOrCreateDatabaseKey(
                databaseExists = true,
                databaseIsPlaintext = true,
                encryptedTemporaryExists = encryptedTemporary.exists()
            )
            val pending = encryptor.migrateIfNeeded(databaseFile, databaseKey!!)
            assertNotNull(
                "Plaintext migration must produce a pending completion token",
                pending
            )

            assertFalse(
                "The canonical database must already be encrypted at the crash boundary",
                encryptor.isPlaintextDatabase(databaseFile)
            )
            assertTrue(
                "The verified plaintext rollback copy must survive until encrypted Room open succeeds",
                plaintextBackup.isFile
            )
            assertFalse(
                "Encrypted promotion must have consumed the temporary candidate",
                encryptedTemporary.exists()
            )

            // The marker is written only after promotion has completed. The method then returns
            // without opening Room and without calling complete(); Orchestrator terminates this
            // target process before the next ordered test method starts.
            FileOutputStream(markerFile).use { output ->
                output.write(Process.myPid().toString().toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            preparedForRestart = true
        } finally {
            runCatching { plaintextRoom?.close() }
            databaseKey?.fill(0)
            if (!preparedForRestart) cleanupFixture()
        }
    }

    @Test
    fun bRecoverPendingMigrationAfterProcessDeathAndCompleteOnlyAfterRoomOpens() {
        System.loadLibrary("sqlcipher")
        assertTrue(
            "The first Orchestrator invocation must leave its process marker",
            markerFile.isFile
        )
        val previousPid = markerFile.readText(Charsets.US_ASCII).trim().toInt()
        assertNotEquals(
            "Android Test Orchestrator must restart the target application process",
            previousPid,
            Process.myPid()
        )

        val encryptor = PlaintextDatabaseEncryptor()
        val keyStore = newKeyStore()
        val issuedPassphrases = mutableListOf<ByteArray>()
        var encryptedRoom: GalaxyDatabase? = null
        var reopenedRoom: GalaxyDatabase? = null
        var databaseKey: ByteArray? = null

        try {
            assertTrue("The promoted encrypted database must survive process death", databaseFile.isFile)
            assertFalse(
                "The promoted canonical database must not have a plaintext SQLite header",
                encryptor.isPlaintextDatabase(databaseFile)
            )
            assertTrue(
                "The plaintext rollback copy must still exist after process death",
                plaintextBackup.isFile
            )

            databaseKey = keyStore.getOrCreateDatabaseKey(
                databaseExists = true,
                databaseIsPlaintext = false,
                encryptedTemporaryExists = encryptedTemporary.exists()
            )
            val pending = try {
                encryptor.migrateIfNeeded(databaseFile, databaseKey!!)
            } finally {
                databaseKey?.fill(0)
                databaseKey = null
            }
            assertNotNull(
                "Startup recovery must reconstruct a pending migration from the encrypted DB and plaintext backup",
                pending
            )
            assertTrue(
                "Recovery must retain the plaintext rollback copy until Room opens successfully",
                plaintextBackup.isFile
            )

            encryptedRoom = buildEncryptedRoom(keyStore, issuedPassphrases)
            val encryptedDatabase = encryptedRoom.openHelper.writableDatabase
            assertHealthyWalDatabase(encryptedDatabase)
            assertPreservedRows(encryptedDatabase)

            // Match the production ordering: migration completion is permitted only after the
            // encrypted Room database has successfully opened and its original rows are readable.
            encryptor.complete(pending)
            assertFalse(
                "The plaintext rollback copy must be removed only after successful encrypted Room open",
                plaintextBackup.exists()
            )

            encryptedDatabase.execSQL(
                """
                INSERT INTO conversation_messages (
                    id, characterId, role, content, createdAtEpochMillis
                ) VALUES ('after-process-death', 'lila', 'ASSISTANT', 'Recovered after process death', 323456789)
                """.trimIndent()
            )

            encryptedRoom.close()
            encryptedRoom = null
            assertTrue(
                "Closing Room must zero every retained SQLCipher passphrase",
                issuedPassphrases.isNotEmpty() && issuedPassphrases.all { buffer ->
                    buffer.all { it == 0.toByte() }
                }
            )

            reopenedRoom = buildEncryptedRoom(keyStore, issuedPassphrases)
            val reopenedDatabase = reopenedRoom.openHelper.writableDatabase
            assertHealthyWalDatabase(reopenedDatabase)
            reopenedDatabase.query(
                "SELECT content FROM conversation_messages WHERE id = ?",
                arrayOf("after-process-death")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Recovered after process death", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }

            reopenedRoom.close()
            reopenedRoom = null
            assertTrue(
                "Closing the reopened helper must zero every SQLCipher passphrase",
                issuedPassphrases.all { buffer -> buffer.all { it == 0.toByte() } }
            )
        } finally {
            runCatching { encryptedRoom?.close() }
            runCatching { reopenedRoom?.close() }
            databaseKey?.fill(0)
            issuedPassphrases.forEach { it.fill(0) }
            cleanupFixture()
        }
    }

    private fun newKeyStore() = EncryptedDatabaseKeyStore(
        context = context,
        preferencesName = PREFERENCES_NAME,
        wrappingKeyAlias = WRAPPING_KEY_ALIAS
    )

    private fun buildEncryptedRoom(
        keyStore: EncryptedDatabaseKeyStore,
        issuedPassphrases: MutableList<ByteArray>
    ): GalaxyDatabase {
        val passphraseProvider = {
            val key = keyStore.getOrCreateDatabaseKey(
                databaseExists = databaseFile.isFile,
                databaseIsPlaintext = false,
                encryptedTemporaryExists = encryptedTemporary.exists()
            )
            try {
                Base64.encode(key, Base64.NO_WRAP).also(issuedPassphrases::add)
            } finally {
                key.fill(0)
            }
        }
        return Room.databaseBuilder(context, GalaxyDatabase::class.java, DATABASE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(ZeroizingSqlCipherOpenHelperFactory(passphraseProvider))
            .build()
    }

    private fun insertSyntheticRows(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO character_profiles (
                id, displayName, description, personaDefinition, avatarId,
                voiceId, modelProviderId, isBuiltIn, avatarImageUri
            ) VALUES (
                'lila', 'Lila', 'Companion', 'Stable shared identity',
                'avatar.fixed', 'voice.default', 'provider.pending', 1, 'content://lila/avatar'
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO conversation_messages (
                id, characterId, role, content, createdAtEpochMillis
            ) VALUES ('before-process-death', 'lila', 'USER', 'Preserve this conversation', 123456789)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_items (
                id, scope, ownerCharacterId, content, importance,
                createdAtEpochMillis, updatedAtEpochMillis
            ) VALUES ('lila-memory', 'CHARACTER', 'lila', 'Preserve this memory', 8, 111, 222)
            """.trimIndent()
        )
    }

    private fun assertHealthyWalDatabase(database: SupportSQLiteDatabase) {
        database.query("PRAGMA journal_mode").use { cursor ->
            assertTrue("SQLCipher must report a journal mode", cursor.moveToFirst())
            assertEquals("wal", cursor.getString(0).lowercase())
        }
        database.query("PRAGMA integrity_check").use { cursor ->
            assertTrue("SQLCipher integrity_check must return a row", cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0).lowercase())
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertPreservedRows(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT displayName, avatarImageUri FROM character_profiles WHERE id = ?",
            arrayOf("lila")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Lila", cursor.getString(0))
            assertEquals("content://lila/avatar", cursor.getString(1))
            assertFalse(cursor.moveToNext())
        }
        database.query(
            "SELECT content, createdAtEpochMillis FROM conversation_messages WHERE id = ?",
            arrayOf("before-process-death")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Preserve this conversation", cursor.getString(0))
            assertEquals(123456789L, cursor.getLong(1))
            assertFalse(cursor.moveToNext())
        }
        database.query(
            "SELECT content, importance, ownerCharacterId FROM memory_items WHERE id = ?",
            arrayOf("lila-memory")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Preserve this memory", cursor.getString(0))
            assertEquals(8, cursor.getInt(1))
            assertEquals("lila", cursor.getString(2))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun cleanupFixture() {
        markerFile.delete()
        val files = listOf(
            "",
            "-wal",
            "-shm",
            "-journal",
            ".plaintext-backup",
            ".plaintext-backup-wal",
            ".plaintext-backup-shm",
            ".plaintext-backup-journal",
            ".encrypted-tmp",
            ".encrypted-failed",
            ".encrypted-failed-wal",
            ".encrypted-failed-shm",
            ".encrypted-failed-journal"
        )
        files.forEach { suffix -> File(databaseFile.path + suffix).delete() }
        runCatching { context.deleteSharedPreferences(PREFERENCES_NAME) }
        runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
                if (containsAlias(WRAPPING_KEY_ALIAS)) deleteEntry(WRAPPING_KEY_ALIAS)
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "galaxy-migration-process-death.db"
        const val MARKER_NAME = "galaxy-migration-process-death.pid"
        const val PREFERENCES_NAME = "galaxy_test_migration_process_death_key"
        const val WRAPPING_KEY_ALIAS = "galaxy_test_migration_process_death_wrap"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
