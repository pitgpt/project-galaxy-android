package com.pitgpt.galaxy.data.local

import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZeroizingSqlCipherRoomConnectionPoolInstrumentedTest {
    @Test
    fun roomWalSecondaryConnectionKeepsPassphraseValidUntilHelperCloses() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val wrappingAlias = "galaxy_test_room_wrap_$suffix"
        val preferencesName = "galaxy_test_room_key_$suffix"
        val databaseName = "galaxy-room-pool-$suffix.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val keyStore = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        val issuedPassphrases = mutableListOf<ByteArray>()
        val executor = Executors.newFixedThreadPool(2)
        val transactionStarted = CountDownLatch(1)
        val releaseTransaction = CountDownLatch(1)
        var roomDatabase: GalaxyDatabase? = null
        var transactionFuture: Future<*>? = null

        try {
            val database = Room.databaseBuilder(
                context,
                GalaxyDatabase::class.java,
                databaseName
            )
                .openHelperFactory(
                    ZeroizingSqlCipherOpenHelperFactory(
                        passphraseProvider = {
                            val key = keyStore.getOrCreateDatabaseKey(
                                databaseExists = databaseFile.exists(),
                                databaseIsPlaintext = false,
                                encryptedTemporaryExists = false
                            )
                            try {
                                Base64.encode(key, Base64.NO_WRAP).also(issuedPassphrases::add)
                            } finally {
                                key.fill(0)
                            }
                        }
                    )
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
            roomDatabase = database

            val sqlDatabase = database.openHelper.writableDatabase
            assertEquals("A fresh Room database should request one passphrase", 1, issuedPassphrases.size)
            assertTrue(
                "The SQLCipher passphrase must remain valid while the Room helper is open",
                issuedPassphrases.single().any { it != 0.toByte() }
            )

            transactionFuture = executor.submit {
                sqlDatabase.beginTransaction()
                try {
                    transactionStarted.countDown()
                    check(releaseTransaction.await(10, TimeUnit.SECONDS)) {
                        "Timed out waiting to release the primary WAL connection."
                    }
                    sqlDatabase.setTransactionSuccessful()
                } finally {
                    sqlDatabase.endTransaction()
                }
            }

            val secondaryConnectionQuery = executor.submit<Int> {
                check(transactionStarted.await(10, TimeUnit.SECONDS)) {
                    "The primary WAL connection did not enter its transaction."
                }
                sqlDatabase.query("SELECT COUNT(*) FROM sqlite_schema").use { cursor ->
                    check(cursor.moveToFirst()) {
                        "The secondary SQLCipher connection could not read sqlite_schema."
                    }
                    cursor.getInt(0)
                }
            }

            assertTrue(
                "The WAL secondary connection should read the Room schema",
                secondaryConnectionQuery.get(15, TimeUnit.SECONDS) > 0
            )
            releaseTransaction.countDown()
            transactionFuture!!.get(15, TimeUnit.SECONDS)

            database.close()
            roomDatabase = null
            assertTrue(
                "Closing Room must zero the passphrase after the connection pool is closed",
                issuedPassphrases.single().all { it == 0.toByte() }
            )
        } finally {
            releaseTransaction.countDown()
            runCatching { transactionFuture?.get(2, TimeUnit.SECONDS) }
            executor.shutdownNow()
            runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
            runCatching { roomDatabase?.close() }
            issuedPassphrases.forEach { it.fill(0) }
            runCatching {
                KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                    load(null)
                    if (containsAlias(wrappingAlias)) deleteEntry(wrappingAlias)
                }
            }
            runCatching { context.deleteSharedPreferences(preferencesName) }
            context.deleteDatabase(databaseName)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(databaseFile.path + suffix).delete()
            }
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
