package com.pitgpt.galaxy.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Supplies a disposable passphrase buffer to SQLCipher.
 *
 * SQLCipher 4.20 retains the supplied byte array in SQLiteOpenHelper and in the database
 * connection-pool configuration. WAL may open additional native connections after the first
 * connection has been created, and each connection uses that same array to apply the key.
 * Therefore the buffer must remain valid for the full helper lifetime and can only be zeroed
 * after the delegate is closed. This is a deliberate availability/security tradeoff: the
 * passphrase remains in the app heap while the database helper is open.
 */
internal class ZeroizingSqlCipherOpenHelperFactory(
    private val passphraseProvider: () -> ByteArray,
    private val enableWriteAheadLogging: Boolean = true
) : SupportSQLiteOpenHelper.Factory {
    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration
    ): SupportSQLiteOpenHelper = ZeroizingSqlCipherOpenHelper(
        configuration = configuration,
        passphraseProvider = passphraseProvider,
        enableWriteAheadLogging = enableWriteAheadLogging
    )
}

internal class ZeroizingSqlCipherOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val passphraseProvider: () -> ByteArray,
    enableWriteAheadLogging: Boolean
) : SupportSQLiteOpenHelper {
    private var delegate: SupportSQLiteOpenHelper? = null
    private var openedDatabase: SupportSQLiteDatabase? = null
    private var activePassphrase: ByteArray? = null
    private var writeAheadLoggingEnabled = enableWriteAheadLogging

    override val databaseName: String?
        get() = configuration.name

    @Synchronized
    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        writeAheadLoggingEnabled = enabled
        delegate?.setWriteAheadLoggingEnabled(enabled)
    }

    override val writableDatabase: SupportSQLiteDatabase
        @Synchronized get() = open { it.writableDatabase }

    override val readableDatabase: SupportSQLiteDatabase
        @Synchronized get() = open { it.readableDatabase }

    @Synchronized
    override fun close() {
        discardDelegate()
    }

    private fun open(
        openDatabase: (SupportSQLiteOpenHelper) -> SupportSQLiteDatabase
    ): SupportSQLiteDatabase {
        val currentDelegate = delegate
        val currentDatabase = openedDatabase
        if (currentDelegate != null && currentDatabase?.isOpen == true) {
            return try {
                openDatabase(currentDelegate).also { openedDatabase = it }
            } catch (failure: Throwable) {
                if (currentDatabase.isOpen) throw failure
                discardDelegate(failure)
                throw failure
            }
        }

        if (currentDelegate != null) discardDelegate()
        return openFresh(openDatabase)
    }

    private fun openFresh(
        openDatabase: (SupportSQLiteOpenHelper) -> SupportSQLiteDatabase
    ): SupportSQLiteDatabase {
        val passphrase = passphraseProvider()
        if (passphrase.isEmpty()) {
            passphrase.fill(0)
            error("The SQLCipher passphrase provider returned an empty buffer.")
        }

        val helper = try {
            SupportOpenHelperFactory(
                passphrase,
                null,
                writeAheadLoggingEnabled
            ).create(configuration)
        } catch (failure: Throwable) {
            passphrase.fill(0)
            throw failure
        }

        delegate = helper
        activePassphrase = passphrase
        return try {
            openDatabase(helper).also { openedDatabase = it }
        } catch (failure: Throwable) {
            discardDelegate(failure)
            throw failure
        }
    }

    private fun discardDelegate(primaryFailure: Throwable? = null) {
        val currentDelegate = delegate
        delegate = null
        openedDatabase = null

        try {
            currentDelegate?.close()
        } catch (closeFailure: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(closeFailure)
            } else {
                throw closeFailure
            }
        } finally {
            activePassphrase?.fill(0)
            activePassphrase = null
        }
    }
}
