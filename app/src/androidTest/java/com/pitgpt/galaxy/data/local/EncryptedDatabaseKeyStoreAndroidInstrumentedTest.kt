package com.pitgpt.galaxy.data.local

import android.util.Base64
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
                "The first SQLCipher passphrase buffer must be zeroed after open",
                issuedPassphrases[0].all { it == 0.toByte() }
            )
            firstDatabase.execSQL(
                "INSERT INTO keystore_round_trip(value) VALUES ('android-keystore-round-trip')"
            )

            helper!!.close()
            assertFalse("Closing the helper must close the first SQLCipher connection", firstDatabase.isOpen)

            val reopenedDatabase = helper!!.writableDatabase
            assertEquals("A closed helper must obtain a fresh passphrase", 2, issuedPassphrases.size)
            assertTrue(
                "The reopened SQLCipher passphrase buffer must also be zeroed",
                issuedPassphrases[1].all { it == 0.toByte() }
            )
            reopenedDatabase.query("SELECT value FROM keystore_round_trip").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("android-keystore-round-trip", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
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

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
