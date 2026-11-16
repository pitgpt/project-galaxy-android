package com.pitgpt.galaxy.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Supplies a disposable passphrase buffer to SQLCipher.
 *
 * sqlcipher-android retains the supplied byte array in its SQLiteOpenHelper. The buffer is
 * therefore zeroed immediately after a successful open. If the database is later closed,
 * the delegate is discarded and a fresh buffer is requested for the next open.
 *
 * The third SupportOpenHelperFactory argument controls WAL; it does not clear the passphrase.
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
        return try {
            openDatabase(helper).also { openedDatabase = it }
        } catch (failure: Throwable) {
            discardDelegate(failure)
            throw failure
        } finally {
            passphrase.fill(0)
        }
    }

    private fun discardDelegate(primaryFailure: Throwable? = null) {
        val currentDelegate = delegate
        delegate = null
        openedDatabase = null
        if (currentDelegate == null) return

        try {
            currentDelegate.close()
        } catch (closeFailure: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(closeFailure)
            } else {
                throw closeFailure
            }
        }
    }
}
