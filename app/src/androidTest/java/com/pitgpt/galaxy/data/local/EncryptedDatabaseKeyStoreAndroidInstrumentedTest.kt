package com.pitgpt.galaxy.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseKeyStoreAndroidInstrumentedTest {
    @Test
    fun productionKeyStoreUnwrapFeedsSqlCipherAndSurvivesHelperReopen() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_db_wrap_$suffix"
        val preferencesName = "galaxy_test_database_key_$suffix"
        val databaseName = "keystore-sqlcipher-$suffix.db"
        val keyStoreUnderTest = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        var firstKey: ByteArray? = null
        var expectedKey: ByteArray? = null
        var secondKey: ByteArray? = null
        var helper: SupportSQLiteOpenHelper? = null
        val issuedPassphrases = mutableListOf<ByteArray>()

        try {
            firstKey = keyStoreUnderTest.getOrCreateDatabaseKey(
                databaseExists = false,
                databaseIsPlaintext = false,
                encryptedTemporaryExists = false
            )
            expectedKey = firstKey!!.copyOf()
            firstKey!!.fill(0)

            secondKey = keyStoreUnderTest.getOrCreateDatabaseKey(
                databaseExists = true,
                databaseIsPlaintext = false,
                encryptedTemporaryExists = false
            )
            assertArrayEquals(
                "The real Android Keystore unwrap must reproduce the stored SQLCipher key",
                expectedKey!!,
                secondKey!!
            )
            secondKey!!.fill(0)

            val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE keystore_round_trip (value TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        error("The isolated Keystore/SQLCipher test has no migrations.")
                    }
                })
                .build()

            helper = ZeroizingSqlCipherOpenHelperFactory(
                passphraseProvider = {
                    val key = keyStoreUnderTest.getOrCreateDatabaseKey(
                        databaseExists = true,
                        databaseIsPlaintext = false,
                        encryptedTemporaryExists = false
                    )
                    try {
                        Base64.encode(key, Base64.NO_WRAP).also(issuedPassphrases::add)
                    } finally {
                        key.fill(0)
                    }
                },
                enableWriteAheadLogging = true
            ).create(configuration)

            val firstDatabase = helper!!.writableDatabase
            assertEquals(1, issuedPassphrases.size)
            assertTrue(
                "The first SQLCipher passphrase must remain valid while the helper is open",
                issuedPassphrases[0].any { it != 0.toByte() }
            )
            firstDatabase.execSQL(
                "INSERT INTO keystore_round_trip(value) VALUES ('android-keystore-round-trip')"
            )

            helper!!.close()
            assertFalse("Closing the helper must close the first SQLCipher connection", firstDatabase.isOpen)
            assertTrue(
                "Closing the helper must zero the first passphrase after the connection pool closes",
                issuedPassphrases[0].all { it == 0.toByte() }
            )

            val reopenedDatabase = helper!!.writableDatabase
            assertEquals("A closed helper must obtain a fresh passphrase", 2, issuedPassphrases.size)
            assertTrue(
                "The reopened SQLCipher passphrase must remain valid while the helper is open",
                issuedPassphrases[1].any { it != 0.toByte() }
            )
            reopenedDatabase.query("SELECT value FROM keystore_round_trip").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("android-keystore-round-trip", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }

            helper!!.close()
            assertTrue(
                "Closing the reopened helper must zero its passphrase",
                issuedPassphrases[1].all { it == 0.toByte() }
            )
        } finally {
            runCatching { helper?.close() }
            issuedPassphrases.forEach { it.fill(0) }
            firstKey?.fill(0)
            expectedKey?.fill(0)
            secondKey?.fill(0)
            runCatching {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                    load(null)
                    if (containsAlias(wrappingAlias)) deleteEntry(wrappingAlias)
                }
            }
            runCatching { context.deleteSharedPreferences(preferencesName) }
            context.deleteDatabase(databaseName)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(context.getDatabasePath(databaseName).path + suffix).delete()
            }
        }
    }


    @Test
    fun migratesPlaintextRoomDatabaseWithAndroidKeystoreKeyAndReopensAfterCompletion() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_migration_wrap_$suffix"
        val preferencesName = "galaxy_test_migration_key_$suffix"
        val databaseName = "galaxy-keystore-migration-$suffix.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val plaintextBackup = File(databaseFile.parentFile, "${databaseFile.name}.plaintext-backup")
        val encryptedTemporary = File(databaseFile.parentFile, "${databaseFile.name}.encrypted-tmp")
        val keyStoreUnderTest = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        val encryptor = PlaintextDatabaseEncryptor()
        val issuedPassphrases = mutableListOf<ByteArray>()
        var databaseKey: ByteArray? = null
        var plaintextRoom: GalaxyDatabase? = null
        var encryptedRoom: GalaxyDatabase? = null
        var reopenedRoom: GalaxyDatabase? = null

        try {
            plaintextRoom = Room.databaseBuilder(
                context,
                GalaxyDatabase::class.java,
                databaseName
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()

            val plaintextDatabase = plaintextRoom.openHelper.writableDatabase
            plaintextDatabase.execSQL(
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
            plaintextDatabase.execSQL(
                """
                INSERT INTO conversation_messages (
                    id, characterId, role, content, createdAtEpochMillis
                ) VALUES ('before-restart', 'lila', 'USER', 'Preserve this conversation', 123456789)
                """.trimIndent()
            )
            plaintextDatabase.execSQL(
                """
                INSERT INTO memory_items (
                    id, scope, ownerCharacterId, content, importance,
                    createdAtEpochMillis, updatedAtEpochMillis
                ) VALUES ('lila-memory', 'CHARACTER', 'lila', 'Preserve this memory', 8, 111, 222)
                """.trimIndent()
            )
            plaintextRoom.close()
            plaintextRoom = null

            assertTrue(
                "The isolated fixture must begin as a plaintext Room database",
                encryptor.isPlaintextDatabase(databaseFile)
            )
            databaseKey = keyStoreUnderTest.getOrCreateDatabaseKey(
                databaseExists = databaseFile.isFile,
                databaseIsPlaintext = encryptor.isPlaintextDatabase(databaseFile),
                encryptedTemporaryExists = encryptedTemporary.exists()
            )
            val pendingMigration = checkNotNull(
                encryptor.migrateIfNeeded(databaseFile, databaseKey!!)
            ) {
                "A plaintext Room fixture must produce a pending encrypted migration."
            }
            databaseKey!!.fill(0)
            databaseKey = null

            assertFalse(
                "The canonical database must be encrypted after migration",
                encryptor.isPlaintextDatabase(databaseFile)
            )
            assertTrue(
                "The verified plaintext rollback copy must remain until Room opens the encrypted database",
                plaintextBackup.isFile
            )

            encryptedRoom = buildEncryptedRoomDatabase(
                context,
                databaseName,
                keyStoreUnderTest,
                issuedPassphrases
            )
            val encryptedDatabase = encryptedRoom.openHelper.writableDatabase
            assertHealthyWalDatabase(encryptedDatabase)
            assertPreservedRows(encryptedDatabase)

            encryptedRoom.close()
            encryptedRoom = null
            assertTrue(
                "Closing Room must zero every SQLCipher passphrase buffer",
                issuedPassphrases.isNotEmpty() && issuedPassphrases.all { buffer ->
                    buffer.all { it == 0.toByte() }
                }
            )

            encryptor.complete(pendingMigration)
            assertFalse(
                "The plaintext rollback copy may be removed only after a successful encrypted Room open",
                plaintextBackup.exists()
            )

            reopenedRoom = buildEncryptedRoomDatabase(
                context,
                databaseName,
                keyStoreUnderTest,
                issuedPassphrases
            )
            val reopenedDatabase = reopenedRoom.openHelper.writableDatabase
            assertHealthyWalDatabase(reopenedDatabase)
            assertPreservedRows(reopenedDatabase)
            reopenedDatabase.execSQL(
                """
                INSERT INTO conversation_messages (
                    id, characterId, role, content, createdAtEpochMillis
                ) VALUES ('after-restart', 'lila', 'ASSISTANT', 'Continue after reopen', 223456789)
                """.trimIndent()
            )

            reopenedRoom.close()
            reopenedRoom = null
            assertTrue(
                "Closing the reopened Room helper must zero its SQLCipher passphrase buffers",
                issuedPassphrases.all { buffer -> buffer.all { it == 0.toByte() } }
            )

            val finalRoom = buildEncryptedRoomDatabase(
                context,
                databaseName,
                keyStoreUnderTest,
                issuedPassphrases
            )
            try {
                val finalDatabase = finalRoom.openHelper.writableDatabase
                assertHealthyWalDatabase(finalDatabase)
                finalDatabase.query(
                    "SELECT content FROM conversation_messages WHERE id = ?",
                    arrayOf("after-restart")
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Continue after reopen", cursor.getString(0))
                    assertFalse(cursor.moveToNext())
                }
            } finally {
                finalRoom.close()
            }
            assertTrue(
                "Every SQLCipher passphrase buffer must be zero after the final close",
                issuedPassphrases.all { buffer -> buffer.all { it == 0.toByte() } }
            )
        } finally {
            runCatching { plaintextRoom?.close() }
            runCatching { encryptedRoom?.close() }
            runCatching { reopenedRoom?.close() }
            databaseKey?.fill(0)
            issuedPassphrases.forEach { it.fill(0) }
            runCatching {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                    load(null)
                    if (containsAlias(wrappingAlias)) deleteEntry(wrappingAlias)
                }
            }
            runCatching { context.deleteSharedPreferences(preferencesName) }
            context.deleteDatabase(databaseName)
            listOf(
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
            ).forEach { suffixToDelete ->
                File(databaseFile.parentFile, databaseFile.name + suffixToDelete).delete()
            }
        }
    }

    private fun buildEncryptedRoomDatabase(
        context: Context,
        databaseName: String,
        keyStoreUnderTest: EncryptedDatabaseKeyStore,
        issuedPassphrases: MutableList<ByteArray>
    ): GalaxyDatabase {
        val databaseFile = context.getDatabasePath(databaseName)
        return Room.databaseBuilder(context, GalaxyDatabase::class.java, databaseName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(
                ZeroizingSqlCipherOpenHelperFactory(
                    passphraseProvider = {
                        val key = keyStoreUnderTest.getOrCreateDatabaseKey(
                            databaseExists = databaseFile.isFile,
                            databaseIsPlaintext = false,
                            encryptedTemporaryExists = File(
                                databaseFile.parentFile,
                                "${databaseFile.name}.encrypted-tmp"
                            ).exists()
                        )
                        try {
                            Base64.encode(key, Base64.NO_WRAP).also(issuedPassphrases::add)
                        } finally {
                            key.fill(0)
                        }
                    }
                )
            )
            .build()
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
            arrayOf("before-restart")
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

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
