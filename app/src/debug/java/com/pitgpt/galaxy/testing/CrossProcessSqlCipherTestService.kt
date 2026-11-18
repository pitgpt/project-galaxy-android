package com.pitgpt.galaxy.testing

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Base64
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pitgpt.galaxy.data.local.EncryptedDatabaseKeyStore
import com.pitgpt.galaxy.data.local.GalaxyDatabase
import com.pitgpt.galaxy.data.local.ZeroizingSqlCipherOpenHelperFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Debug-variant-only remote process used by SQLCipher instrumentation tests.
 * The service is non-exported and accepts only opaque fixture identifiers and database settings.
 */
class CrossProcessSqlCipherTestService : Service() {
    @Volatile
    private var database: GalaxyDatabase? = null

    private val passphrases = CopyOnWriteArrayList<ByteArray>()
    private val pendingClaims = ConcurrentHashMap<String, CountDownLatch>()

    private lateinit var workerThread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("GalaxySqlCipherTestService").apply { start() }
        messenger = Messenger(
            Handler(workerThread.looper) { message ->
                handleMessage(message)
                true
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        runCatching { database?.close() }
        database = null
        passphrases.forEach { it.fill(0) }
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun handleMessage(message: Message) {
        val replyTo = message.replyTo ?: return
        try {
            when (message.what) {
                MSG_OPEN -> {
                    val data = message.data
                    openDatabase(
                        databaseName = requireNotNull(data.getString(KEY_DATABASE_NAME)),
                        preferencesName = requireNotNull(data.getString(KEY_PREFERENCES_NAME)),
                        wrappingAlias = requireNotNull(data.getString(KEY_WRAPPING_ALIAS))
                    )
                    val journalMode = requireNotNull(database)
                        .openHelper.writableDatabase
                        .query("PRAGMA journal_mode")
                        .use { cursor ->
                            check(cursor.moveToFirst()) { "Could not read remote SQLCipher journal mode" }
                            cursor.getString(0)
                        }
                    check(journalMode.equals("wal", ignoreCase = true)) {
                        "Remote SQLCipher helper did not enter WAL mode"
                    }
                    reply(replyTo, MSG_OPENED) {
                        putInt(KEY_PROCESS_ID, android.os.Process.myPid())
                        putBoolean(KEY_WAL_CONFIRMED, true)
                    }
                }

                MSG_ARM_CLAIM -> {
                    val data = message.data
                    val roundId = requireNotNull(data.getString(KEY_ROUND_ID))
                    val promptId = requireNotNull(data.getString(KEY_PROMPT_ID))
                    val gate = CountDownLatch(1)
                    check(pendingClaims.putIfAbsent(roundId, gate) == null) {
                        "A claim round with this identifier is already pending"
                    }
                    Thread({
                        try {
                            check(gate.await(10, TimeUnit.SECONDS)) {
                                "The cross-process claim start signal timed out"
                            }
                            val claimed = runBlocking(Dispatchers.IO) {
                                requireNotNull(database)
                                    .comfyUiJobDao()
                                    .markSubmissionStarted(promptId) == 1
                            }
                            reply(replyTo, MSG_CLAIM_RESULT) {
                                putString(KEY_ROUND_ID, roundId)
                                putBoolean(KEY_CLAIMED, claimed)
                            }
                        } catch (failure: Throwable) {
                            replyError(replyTo, failure, roundId)
                        } finally {
                            pendingClaims.remove(roundId, gate)
                        }
                    }, "GalaxySqlCipherClaim-$roundId").start()

                    reply(replyTo, MSG_CLAIM_ARMED) {
                        putString(KEY_ROUND_ID, roundId)
                    }
                }

                MSG_GO_CLAIM -> {
                    val roundId = requireNotNull(message.data.getString(KEY_ROUND_ID))
                    val gate = requireNotNull(pendingClaims[roundId]) {
                        "No armed cross-process claim exists"
                    }
                    gate.countDown()
                }

                MSG_READ_JOB -> {
                    val promptId = requireNotNull(message.data.getString(KEY_PROMPT_ID))
                    val record = runBlocking(Dispatchers.IO) {
                        requireNotNull(database).comfyUiJobDao().get(promptId)
                    }
                    reply(replyTo, MSG_JOB_READ) {
                        putBoolean(KEY_FOUND, record != null)
                        putBoolean(KEY_SUBMISSION_STARTED, record?.submissionStarted == true)
                    }
                }

                MSG_CLOSE -> {
                    // Let any in-flight test claim finish before closing the remote connection pool.
                    pendingClaims.values.forEach { it.countDown() }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (pendingClaims.isNotEmpty() && System.nanoTime() < deadline) {
                        Thread.sleep(10)
                    }
                    check(pendingClaims.isEmpty()) { "Remote SQLCipher claims did not drain before close" }
                    val current = database
                    database = null
                    current?.close()
                    reply(replyTo, MSG_CLOSED) {
                        putInt(KEY_PASSPHRASE_COUNT, passphrases.size)
                        putBoolean(
                            KEY_PASSPHRASES_ZEROED,
                            passphrases.isNotEmpty() && passphrases.all { buffer ->
                                buffer.all { it == 0.toByte() }
                            }
                        )
                    }
                }

                else -> error("Unknown cross-process SQLCipher test command")
            }
        } catch (failure: Throwable) {
            replyError(replyTo, failure)
        }
    }

    private fun openDatabase(
        databaseName: String,
        preferencesName: String,
        wrappingAlias: String
    ) {
        check(database == null) { "The remote SQLCipher test database is already open" }
        System.loadLibrary("sqlcipher")

        val databaseFile = getDatabasePath(databaseName)
        val keyStore = EncryptedDatabaseKeyStore(
            context = applicationContext,
            preferencesName = preferencesName,
            wrappingKeyAlias = wrappingAlias
        )
        val opened = Room.databaseBuilder(
            applicationContext,
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
                        Base64.encode(key, Base64.NO_WRAP).also(passphrases::add)
                    } finally {
                        key.fill(0)
                    }
                })
            )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

        try {
            opened.openHelper.writableDatabase
            database = opened
        } catch (failure: Throwable) {
            runCatching { opened.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun reply(
        target: Messenger,
        what: Int,
        configure: Bundle.() -> Unit = {}
    ) {
        target.send(
            Message.obtain(null, what).apply {
                data = Bundle().apply(configure)
            }
        )
    }

    private fun replyError(target: Messenger, failure: Throwable, roundId: String? = null) {
        runCatching {
            reply(target, MSG_ERROR) {
                putString(KEY_ERROR_TYPE, failure.javaClass.simpleName)
                roundId?.let { putString(KEY_ROUND_ID, it) }
            }
        }
    }

    companion object {
        const val MSG_OPEN = 1
        const val MSG_OPENED = 2
        const val MSG_ARM_CLAIM = 3
        const val MSG_CLAIM_ARMED = 4
        const val MSG_GO_CLAIM = 5
        const val MSG_CLAIM_RESULT = 6
        const val MSG_READ_JOB = 7
        const val MSG_JOB_READ = 8
        const val MSG_CLOSE = 9
        const val MSG_CLOSED = 10
        const val MSG_ERROR = 11

        const val KEY_DATABASE_NAME = "database_name"
        const val KEY_PREFERENCES_NAME = "preferences_name"
        const val KEY_WRAPPING_ALIAS = "wrapping_alias"
        const val KEY_PROCESS_ID = "process_id"
        const val KEY_WAL_CONFIRMED = "wal_confirmed"
        const val KEY_ROUND_ID = "round_id"
        const val KEY_PROMPT_ID = "prompt_id"
        const val KEY_CLAIMED = "claimed"
        const val KEY_FOUND = "found"
        const val KEY_SUBMISSION_STARTED = "submission_started"
        const val KEY_PASSPHRASE_COUNT = "passphrase_count"
        const val KEY_PASSPHRASES_ZEROED = "passphrases_zeroed"
        const val KEY_ERROR_TYPE = "error_type"
    }
}
