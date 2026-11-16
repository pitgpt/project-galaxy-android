package com.pitgpt.galaxy.data.local

import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZeroizingSqlCipherOpenHelperFactoryInstrumentedTest {
    @Test
    fun passphraseBufferIsZeroedAfterOpenAndFreshBufferSupportsReopen() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "sqlcipher-passphrase-" + UUID.randomUUID() + ".db"
        val databaseKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val issuedPassphrases = mutableListOf<ByteArray>()
        val factory = ZeroizingSqlCipherOpenHelperFactory(
            passphraseProvider = {
                Base64.encode(databaseKey, Base64.NO_WRAP).also(issuedPassphrases::add)
            },
            enableWriteAheadLogging = true
        )
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE passphrase_lifecycle (value TEXT NOT NULL)"
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) {
                    error("The isolated passphrase lifecycle database has no migrations.")
                }
            })
            .build()
        val helper = factory.create(configuration)

        try {
            val firstDatabase = helper.writableDatabase
            assertEquals(1, issuedPassphrases.size)
            assertTrue(
                "The passphrase buffer must be zeroed as soon as SQLCipher opens the database",
                issuedPassphrases[0].all { it == 0.toByte() }
            )

            firstDatabase.execSQL(
                "INSERT INTO passphrase_lifecycle(value) VALUES ('survives-reopen')"
            )
            assertTrue(helper.writableDatabase.isOpen)
            assertEquals(
                "An already-open helper must not request another passphrase",
                1,
                issuedPassphrases.size
            )

            helper.close()
            assertFalse("Closing the helper must close its database", firstDatabase.isOpen)

            val reopenedDatabase = helper.writableDatabase
            assertEquals(
                "Reopening must obtain a fresh passphrase buffer from the provider",
                2,
                issuedPassphrases.size
            )
            assertTrue(
                "The fresh passphrase buffer must also be zeroed after opening",
                issuedPassphrases[1].all { it == 0.toByte() }
            )
            reopenedDatabase.query(
                "SELECT value FROM passphrase_lifecycle"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("survives-reopen", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
        } finally {
            try {
                helper.close()
            } finally {
                issuedPassphrases.forEach { it.fill(0) }
                databaseKey.fill(0)
                context.deleteDatabase(databaseName)
                listOf("-wal", "-shm", "-journal").forEach { suffix ->
                    File(context.getDatabasePath(databaseName).path + suffix).delete()
                }
            }
        }
    }
}
