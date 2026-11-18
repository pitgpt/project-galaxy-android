package com.pitgpt.galaxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the SQLCipher helper reached through the real application composition root.
 *
 * The primary thread holds one WAL transaction while a second thread reads sqlite_schema.
 * This forces Room/SQLCipher's connection pool to open a secondary native connection using
 * the passphrase retained by GalaxyDatabaseFactory's ZeroizingSqlCipherOpenHelperFactory.
 * The test is read-only and deliberately leaves the application-owned database open.
 */
@RunWith(AndroidJUnit4::class)
class GalaxyApplicationSqlCipherWalSecondaryConnectionInstrumentedTest {
    @Test
    fun productionCompositionRootKeepsPassphraseValidForWalSecondaryConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as GalaxyApplication
        val database = application.database
        val sqlDatabase = database.openHelper.writableDatabase

        val journalMode = sqlDatabase.query("PRAGMA journal_mode").use { cursor ->
            assertTrue("Room must return its configured journal mode", cursor.moveToFirst())
            cursor.getString(0).lowercase()
        }
        assertEquals(
            "The production SQLCipher helper must use WAL so a secondary connection is opened",
            "wal",
            journalMode
        )

        val executor: ExecutorService = Executors.newFixedThreadPool(2)
        val primaryTransactionStarted = CountDownLatch(1)
        val releasePrimaryTransaction = CountDownLatch(1)
        var primaryTransaction: Future<*>? = null

        try {
            primaryTransaction = executor.submit {
                sqlDatabase.beginTransaction()
                try {
                    primaryTransactionStarted.countDown()
                    check(releasePrimaryTransaction.await(10, TimeUnit.SECONDS)) {
                        "Timed out waiting to release the primary WAL connection."
                    }
                    sqlDatabase.setTransactionSuccessful()
                } finally {
                    sqlDatabase.endTransaction()
                }
            }

            val secondaryConnectionQuery = executor.submit<Int> {
                check(primaryTransactionStarted.await(10, TimeUnit.SECONDS)) {
                    "The primary connection did not enter its transaction."
                }
                sqlDatabase.query("SELECT COUNT(*) FROM sqlite_schema").use { cursor ->
                    check(cursor.moveToFirst()) {
                        "The SQLCipher WAL secondary connection could not read sqlite_schema."
                    }
                    cursor.getInt(0)
                }
            }

            assertTrue(
                "The production Room/SQLCipher secondary connection must read the schema",
                secondaryConnectionQuery.get(15, TimeUnit.SECONDS) > 0
            )
            releasePrimaryTransaction.countDown()
            primaryTransaction.get(15, TimeUnit.SECONDS)
            assertTrue("The application-owned Room database must remain open", database.isOpen)
        } finally {
            releasePrimaryTransaction.countDown()
            runCatching { primaryTransaction?.get(2, TimeUnit.SECONDS) }
            executor.shutdownNow()
            assertTrue(
                "The test workers must terminate without closing the application database",
                executor.awaitTermination(2, TimeUnit.SECONDS)
            )
        }
    }
}
