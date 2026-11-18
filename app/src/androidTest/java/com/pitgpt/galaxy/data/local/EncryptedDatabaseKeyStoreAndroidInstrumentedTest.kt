package com.pitgpt.galaxy.data.local

import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun missingWrappingKeyNeverReplacesKeyForExistingEncryptedDatabase() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_missing_wrap_$suffix"
        val preferencesName = "galaxy_test_missing_key_$suffix"
        val emptyPreferencesName = "galaxy_test_empty_key_$suffix"
        val databaseName = "missing-key-sqlcipher-$suffix.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val keyStoreUnderTest = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        var initialKey: ByteArray? = null
        var helper: SupportSQLiteOpenHelper? = null
        var encryptedDatabaseBefore: ByteArray? = null
        val issuedPassphrases = mutableListOf<ByteArray>()

        try {
            initialKey = keyStoreUnderTest.getOrCreateDatabaseKey(
                databaseExists = false,
                databaseIsPlaintext = false,
                encryptedTemporaryExists = false
            )
            assertEquals(32, initialKey!!.size)
            initialKey!!.fill(0)

            val preferences = context.getSharedPreferences(
                preferencesName,
                android.content.Context.MODE_PRIVATE
            )
            val wrappedKeyBefore = preferences.all.values.single() as String

            val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE preserved_ciphertext (value TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        error("The isolated missing-key test has no migrations.")
                    }
                })
                .build()

            helper = ZeroizingSqlCipherOpenHelperFactory(
                passphraseProvider = {
                    val key = keyStoreUnderTest.getOrCreateDatabaseKey(
                        databaseExists = databaseFile.exists(),
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

            helper!!.writableDatabase.execSQL(
                "INSERT INTO preserved_ciphertext(value) VALUES ('keep-existing-encrypted-data')"
            )
            helper!!.close()
            assertTrue(issuedPassphrases.single().all { it == 0.toByte() })
            assertTrue(databaseFile.isFile && databaseFile.length() > 0L)

            encryptedDatabaseBefore = databaseFile.readBytes()
            val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            assertFalse(
                "The test fixture must be an encrypted SQLCipher database",
                encryptedDatabaseBefore!!.copyOfRange(0, sqliteHeader.size)
                    .contentEquals(sqliteHeader)
            )

            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
                assertTrue(containsAlias(wrappingAlias))
                deleteEntry(wrappingAlias)
            }

            assertThrows(IllegalStateException::class.java) {
                keyStoreUnderTest.getOrCreateDatabaseKey(
                    databaseExists = true,
                    databaseIsPlaintext = false,
                    encryptedTemporaryExists = false
                )
            }

            val keyStoreAfterWrappedPayloadFailure =
                KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            assertFalse(
                "A missing wrapping key must not be silently recreated for an encrypted database",
                keyStoreAfterWrappedPayloadFailure.containsAlias(wrappingAlias)
            )
            assertEquals(wrappedKeyBefore, preferences.all.values.single())

            val noPayloadStore = EncryptedDatabaseKeyStore(
                context = context,
                preferencesName = emptyPreferencesName,
                wrappingKeyAlias = wrappingAlias
            )
            assertThrows(IllegalStateException::class.java) {
                noPayloadStore.getOrCreateDatabaseKey(
                    databaseExists = true,
                    databaseIsPlaintext = false,
                    encryptedTemporaryExists = false
                )
            }

            val keyStoreAfterMissingPayloadFailure =
                KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            assertFalse(
                "A missing wrapped payload must not create a replacement key for an encrypted database",
                keyStoreAfterMissingPayloadFailure.containsAlias(wrappingAlias)
            )
            assertTrue(
                context.getSharedPreferences(
                    emptyPreferencesName,
                    android.content.Context.MODE_PRIVATE
                ).all.isEmpty()
            )

            val encryptedDatabaseAfter = databaseFile.readBytes()
            try {
                assertArrayEquals(
                    "Both fail-closed paths must preserve the original encrypted database byte-for-byte",
                    encryptedDatabaseBefore,
                    encryptedDatabaseAfter
                )
            } finally {
                encryptedDatabaseAfter.fill(0)
            }
        } finally {
            runCatching { helper?.close() }
            issuedPassphrases.forEach { it.fill(0) }
            initialKey?.fill(0)
            encryptedDatabaseBefore?.fill(0)
            runCatching {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                    load(null)
                    if (containsAlias(wrappingAlias)) deleteEntry(wrappingAlias)
                }
            }
            runCatching { context.deleteSharedPreferences(preferencesName) }
            runCatching { context.deleteSharedPreferences(emptyPreferencesName) }
            context.deleteDatabase(databaseName)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(databaseFile.path + suffix).delete()
            }
        }
    }

    @Test
    fun missingWrappingKeyPreservesEncryptedMigrationCandidateAndPlaintextBackup() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_candidate_wrap_$suffix"
        val preferencesName = "galaxy_test_candidate_key_$suffix"
        val emptyPreferencesName = "galaxy_test_candidate_empty_$suffix"
        val canonicalName = "migration-candidate-$suffix.db"
        val canonicalFile = context.getDatabasePath(canonicalName)
        val candidateFile = File(canonicalFile.parentFile, canonicalFile.name + ".encrypted-tmp")
        val backupFile = File(canonicalFile.parentFile, canonicalFile.name + ".plaintext-backup")
        canonicalFile.parentFile?.mkdirs()

        val keyStoreUnderTest = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        var databaseKey: ByteArray? = null
        var candidateBytesBefore: ByteArray? = null
        var backupBytesBefore: ByteArray? = null

        try {
            val key = keyStoreUnderTest.getOrCreateDatabaseKey(
                databaseExists = false,
                databaseIsPlaintext = false,
                encryptedTemporaryExists = false
            )
            databaseKey = key
            assertEquals(32, key.size)

            val candidatePassphrase = Base64.encode(key, Base64.NO_WRAP)
            try {
                SqlCipherDatabase.openDatabase(
                    candidateFile.absolutePath,
                    candidatePassphrase,
                    null,
                    SqlCipherDatabase.OPEN_READWRITE or SqlCipherDatabase.CREATE_IF_NECESSARY,
                    null
                ).use { candidate ->
                    candidate.execSQL(
                        "CREATE TABLE candidate_marker (id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)"
                    )
                    candidate.execSQL(
                        "INSERT INTO candidate_marker VALUES ('candidate', 'preserve encrypted candidate')"
                    )
                }
            } finally {
                candidatePassphrase.fill(0)
            }

            FrameworkSQLiteDatabase.openOrCreateDatabase(backupFile, null).use { backup ->
                backup.execSQL(
                    "CREATE TABLE rollback_marker (id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)"
                )
                backup.execSQL(
                    "INSERT INTO rollback_marker VALUES ('backup', 'preserve plaintext rollback copy')"
                )
            }

            assertFalse("The simulated crash state must not have a canonical database", canonicalFile.exists())
            assertTrue(candidateFile.isFile && candidateFile.length() > 0L)
            assertTrue(backupFile.isFile && backupFile.length() > 0L)

            candidateBytesBefore = candidateFile.readBytes()
            backupBytesBefore = backupFile.readBytes()
            val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            assertFalse(
                "The migration candidate must be encrypted SQLCipher data",
                candidateBytesBefore!!.copyOfRange(0, sqliteHeader.size).contentEquals(sqliteHeader)
            )
            assertTrue(
                "The rollback copy must remain a plaintext SQLite database",
                backupBytesBefore!!.copyOfRange(0, sqliteHeader.size).contentEquals(sqliteHeader)
            )

            val preferences = context.getSharedPreferences(
                preferencesName,
                android.content.Context.MODE_PRIVATE
            )
            val wrappedKeyBefore = preferences.all.values.single() as String

            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
                assertTrue(containsAlias(wrappingAlias))
                deleteEntry(wrappingAlias)
            }

            assertThrows(IllegalStateException::class.java) {
                keyStoreUnderTest.getOrCreateDatabaseKey(
                    databaseExists = false,
                    databaseIsPlaintext = false,
                    encryptedTemporaryExists = true
                )
            }
            assertEquals(
                "The existing wrapped payload must not be modified after key lookup fails",
                wrappedKeyBefore,
                preferences.all.values.single()
            )

            val noPayloadStore = EncryptedDatabaseKeyStore(
                context = context,
                preferencesName = emptyPreferencesName,
                wrappingKeyAlias = wrappingAlias
            )
            assertThrows(IllegalStateException::class.java) {
                noPayloadStore.getOrCreateDatabaseKey(
                    databaseExists = false,
                    databaseIsPlaintext = false,
                    encryptedTemporaryExists = true
                )
            }

            val keyStoreAfterFailures = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            assertFalse(
                "An encrypted temporary candidate must never trigger replacement wrapping-key creation",
                keyStoreAfterFailures.containsAlias(wrappingAlias)
            )
            assertTrue(
                context.getSharedPreferences(
                    emptyPreferencesName,
                    android.content.Context.MODE_PRIVATE
                ).all.isEmpty()
            )
            assertFalse(canonicalFile.exists())
            assertArrayEquals(candidateBytesBefore, candidateFile.readBytes())
            assertArrayEquals(backupBytesBefore, backupFile.readBytes())

            val verificationPassphrase = Base64.encode(key, Base64.NO_WRAP)
            try {
                SqlCipherDatabase.openDatabase(
                    candidateFile.absolutePath,
                    verificationPassphrase,
                    null,
                    SqlCipherDatabase.OPEN_READWRITE,
                    null
                ).use { candidate ->
                    candidate.rawQuery(
                        "SELECT value FROM candidate_marker WHERE id = 'candidate'",
                        null
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("preserve encrypted candidate", cursor.getString(0))
                    }
                }
            } finally {
                verificationPassphrase.fill(0)
            }

            FrameworkSQLiteDatabase.openDatabase(
                backupFile.absolutePath,
                null,
                FrameworkSQLiteDatabase.OPEN_READONLY
            ).use { backup ->
                backup.rawQuery(
                    "SELECT value FROM rollback_marker WHERE id = 'backup'",
                    null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("preserve plaintext rollback copy", cursor.getString(0))
                }
            }
        } finally {
            databaseKey?.fill(0)
            candidateBytesBefore?.fill(0)
            backupBytesBefore?.fill(0)
            runCatching {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                    load(null)
                    if (containsAlias(wrappingAlias)) deleteEntry(wrappingAlias)
                }
            }
            runCatching { context.deleteSharedPreferences(preferencesName) }
            runCatching { context.deleteSharedPreferences(emptyPreferencesName) }
            listOf(canonicalFile, candidateFile, backupFile).forEach { file ->
                file.delete()
                listOf("-wal", "-shm", "-journal").forEach { suffix ->
                    File(file.path + suffix).delete()
                }
            }
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
