package com.pitgpt.galaxy.data.providers.comfyui

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.data.local.EncryptedDatabaseKeyStore
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import com.pitgpt.galaxy.data.local.ZeroizingSqlCipherOpenHelperFactory
import com.pitgpt.galaxy.data.local.entity.ComfyUiJobEntity
import com.pitgpt.galaxy.testing.CrossProcessSqlCipherTestService
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private data class SqlCipherTestReply(val what: Int, val data: Bundle)

@RunWith(AndroidJUnit4::class)
class CrossProcessSqlCipherSubmissionClaimInstrumentedTest {
    @Test
    fun independentAndroidProcessesShareAtMostOneKeystoreBackedSubmissionClaim() = runBlocking {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "galaxy-cross-process-$suffix.db"
        val preferencesName = "galaxy_cross_process_key_$suffix"
        val wrappingAlias = "galaxy_cross_process_wrap_$suffix"
        val databaseFile = context.getDatabasePath(databaseName)
        val keyStore = EncryptedDatabaseKeyStore(
            context = context,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        val localPassphrases = CopyOnWriteArrayList<ByteArray>()
        var localDatabase: GalaxyDatabase? = null
        var remoteMessenger: Messenger? = null
        var serviceBound = false
        var remoteClosed = false
        var remoteMayBeOpen = false

        val replies = Channel<SqlCipherTestReply>(Channel.UNLIMITED)
        val replyThread = HandlerThread("GalaxySqlCipherTestReplies").apply { start() }
        val replyMessenger = Messenger(
            Handler(replyThread.looper) { message ->
                // Android recycles a delivered Message after this callback returns.
                // Snapshot the primitive response data before handing it to the test coroutine.
                replies.trySend(SqlCipherTestReply(message.what, Bundle(message.data)))
                true
            }
        )
        val connected = CompletableDeferred<Messenger>()
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
                val messenger = Messenger(service)
                remoteMessenger = messenger
                connected.complete(messenger)
            }

            override fun onServiceDisconnected(name: android.content.ComponentName) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("Remote SQLCipher test service disconnected"))
                }
            }

            override fun onBindingDied(name: android.content.ComponentName) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("Remote SQLCipher test service binding died"))
                }
            }

            override fun onNullBinding(name: android.content.ComponentName) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IllegalStateException("Remote SQLCipher test service returned a null binding"))
                }
            }
        }

        suspend fun awaitReply(expectedWhat: Int, stage: String): SqlCipherTestReply = try {
            withTimeout(15_000) {
                while (true) {
                    val message = replies.receive()
                    if (message.what == CrossProcessSqlCipherTestService.MSG_ERROR) {
                        error(
                            "Remote SQLCipher test service failed during $stage: " +
                                message.data.getString(CrossProcessSqlCipherTestService.KEY_ERROR_TYPE)
                        )
                    }
                    if (message.what == expectedWhat) return@withTimeout message
                }
                error("Expected remote SQLCipher response was not received during $stage")
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for remote SQLCipher reply during $stage (message=$expectedWhat)",
                timeout
            )
        }

        fun sendRemote(what: Int, configure: Bundle.() -> Unit = {}) {
            val remote = requireNotNull(remoteMessenger) { "Remote SQLCipher service is not connected" }
            remote.send(
                Message.obtain(null, what).apply {
                    data = Bundle().apply(configure)
                    replyTo = replyMessenger
                }
            )
        }

        try {
            // Create the wrapped key before the remote process starts. Both processes then
            // unwrap the same device-bound key; neither process receives raw key material over IPC.
            val initialKey = keyStore.getOrCreateDatabaseKey(
                databaseExists = false,
                databaseIsPlaintext = false,
                encryptedTemporaryExists = false
            )
            initialKey.fill(0)

            val openedLocal = Room.databaseBuilder(
                context,
                GalaxyDatabase::class.java,
                databaseName
            )
                .openHelperFactory(
                    ZeroizingSqlCipherOpenHelperFactory(passphraseProvider = {
                        val key = keyStore.getOrCreateDatabaseKey(
                            databaseExists = databaseFile.exists(),
                            databaseIsPlaintext = false,
                            encryptedTemporaryExists = false
                        )
                        try {
                            Base64.encode(key, Base64.NO_WRAP).also(localPassphrases::add)
                        } finally {
                            key.fill(0)
                        }
                    })
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
            localDatabase = openedLocal
            val localDao = openedLocal.comfyUiJobDao()
            val localWritable = openedLocal.openHelper.writableDatabase

            val localProcessId = Process.myPid()
            val intent = Intent(context, CrossProcessSqlCipherTestService::class.java)
            assertTrue(
                "The debug-only cross-process SQLCipher test service must bind",
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            )
            serviceBound = true
            withTimeout(10_000) { connected.await() }

            remoteMayBeOpen = true
            sendRemote(CrossProcessSqlCipherTestService.MSG_OPEN) {
                putString(CrossProcessSqlCipherTestService.KEY_DATABASE_NAME, databaseName)
                putString(CrossProcessSqlCipherTestService.KEY_PREFERENCES_NAME, preferencesName)
                putString(CrossProcessSqlCipherTestService.KEY_WRAPPING_ALIAS, wrappingAlias)
            }
            val openedRemote = awaitReply(CrossProcessSqlCipherTestService.MSG_OPENED, "opening the remote SQLCipher database")
            val remoteProcessId = openedRemote.data.getInt(CrossProcessSqlCipherTestService.KEY_PROCESS_ID)
            assertTrue("The remote service must report a valid process ID", remoteProcessId > 0)
            assertNotEquals(
                "The test service must run in a distinct Android process",
                localProcessId,
                remoteProcessId
            )
            assertTrue(
                "The remote service must confirm its SQLCipher helper entered WAL mode",
                openedRemote.data.getBoolean(CrossProcessSqlCipherTestService.KEY_WAL_CONFIRMED)
            )

            repeat(24) { round ->
                val promptId = UUID.randomUUID().toString()
                val requestId = "cross-process-request-$suffix-$round"
                localDao.insert(
                    ComfyUiJobEntity(
                        promptId = promptId,
                        requestId = requestId,
                        createdAtEpochMillis = System.currentTimeMillis() + round,
                        cancelled = false,
                        submissionStarted = false
                    )
                )

                val roundId = round.toString()
                sendRemote(CrossProcessSqlCipherTestService.MSG_ARM_CLAIM) {
                    putString(CrossProcessSqlCipherTestService.KEY_ROUND_ID, roundId)
                    putString(CrossProcessSqlCipherTestService.KEY_PROMPT_ID, promptId)
                }
                val armed = awaitReply(CrossProcessSqlCipherTestService.MSG_CLAIM_ARMED, "arming claim round $round")
                assertEquals(roundId, armed.data.getString(CrossProcessSqlCipherTestService.KEY_ROUND_ID))

                val localStart = CompletableDeferred<Unit>()
                val localClaim = async(Dispatchers.IO) {
                    localStart.await()
                    localDao.markSubmissionStarted(promptId) == 1
                }

                sendRemote(CrossProcessSqlCipherTestService.MSG_GO_CLAIM) {
                    putString(CrossProcessSqlCipherTestService.KEY_ROUND_ID, roundId)
                }
                localStart.complete(Unit)

                val remoteResult = awaitReply(CrossProcessSqlCipherTestService.MSG_CLAIM_RESULT, "collecting claim result for round $round")
                assertEquals(roundId, remoteResult.data.getString(CrossProcessSqlCipherTestService.KEY_ROUND_ID))
                val localWon = localClaim.await()
                val remoteWon = remoteResult.data.getBoolean(CrossProcessSqlCipherTestService.KEY_CLAIMED)
                assertEquals(
                    "Exactly one process may acquire a durable submission claim",
                    1,
                    listOf(localWon, remoteWon).count { it }
                )

                val localRecord = requireNotNull(localDao.get(promptId))
                assertTrue(localRecord.submissionStarted)

                sendRemote(CrossProcessSqlCipherTestService.MSG_READ_JOB) {
                    putString(CrossProcessSqlCipherTestService.KEY_PROMPT_ID, promptId)
                }
                val remoteRecord = awaitReply(CrossProcessSqlCipherTestService.MSG_JOB_READ, "reading job for round $round")
                assertTrue(remoteRecord.data.getBoolean(CrossProcessSqlCipherTestService.KEY_FOUND))
                assertTrue(remoteRecord.data.getBoolean(CrossProcessSqlCipherTestService.KEY_SUBMISSION_STARTED))
            }

            val integrity = localWritable.query("PRAGMA integrity_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("ok", integrity.lowercase())

            sendRemote(CrossProcessSqlCipherTestService.MSG_CLOSE)
            val closedRemote = awaitReply(CrossProcessSqlCipherTestService.MSG_CLOSED, "closing the remote SQLCipher helper")
            remoteClosed = true
            assertTrue(
                "The remote process must have opened its own SQLCipher passphrase buffer",
                closedRemote.data.getInt(CrossProcessSqlCipherTestService.KEY_PASSPHRASE_COUNT) > 0
            )
            assertTrue(
                "The remote helper must zero its passphrase after closing its connection pool",
                closedRemote.data.getBoolean(CrossProcessSqlCipherTestService.KEY_PASSPHRASES_ZEROED)
            )

            openedLocal.close()
            localDatabase = null
            assertTrue(localPassphrases.isNotEmpty())
            assertTrue(
                "The local helper must zero its passphrase after closing its connection pool",
                localPassphrases.all { buffer -> buffer.all { it == 0.toByte() } }
            )
        } finally {
            if (!remoteClosed && remoteMessenger != null) {
                try {
                    sendRemote(CrossProcessSqlCipherTestService.MSG_CLOSE)
                    awaitReply(CrossProcessSqlCipherTestService.MSG_CLOSED, "closing the remote SQLCipher helper")
                    remoteClosed = true
                } catch (_: Throwable) {
                    // If remote closure cannot be confirmed, preserve its test-owned database and key.
                }
            }
            if (serviceBound) runCatching { context.unbindService(serviceConnection) }
            runCatching { localDatabase?.close() }
            localPassphrases.forEach { it.fill(0) }
            if (remoteClosed || !remoteMayBeOpen) {
                runCatching {
                    KeyStore.getInstance("AndroidKeyStore").apply {
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
            replies.close()
            replyThread.quitSafely()
        }
    }
}
