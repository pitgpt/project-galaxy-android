package com.pitgpt.galaxy.data.local

import android.content.Context
import android.database.Cursor
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Converts an existing plaintext Room database to SQLCipher before Room is allowed to open it.
 *
 * The source file is preserved until the encrypted copy has passed integrity, schema-version,
 * and row-count verification. Interrupted promotion is recovered on the next process start.
 */
internal object EncryptedDatabaseBootstrap {
    private const val DATABASE_NAME = "galaxy.db"
    private const val TEMP_SUFFIX = ".encrypted.tmp"
    private const val BACKUP_SUFFIX = ".plaintext.backup"
    private val TABLES = listOf("character_profiles", "conversation_messages", "memory_items")

    @Synchronized
    fun prepare(context: Context): ByteArray {
        System.loadLibrary("sqlcipher")
        val database = context.getDatabasePath(DATABASE_NAME)
        database.parentFile?.let { check(it.exists() || it.mkdirs()) }
        val temporary = File(database.parentFile, DATABASE_NAME + TEMP_SUFFIX)
        val backup = File(database.parentFile, DATABASE_NAME + BACKUP_SUFFIX)
        val keyStore = DatabaseKeyStore(context)

        // Recover a process death between moving the source aside and promoting the encrypted copy.
        if (!database.exists() && backup.exists()) {
            check(backup.renameTo(database)) {
                "Galaxy database recovery could not restore the original local database."
            }
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val savedSidecar = File(backup.path + suffix)
                val originalSidecar = File(database.path + suffix)
                if (savedSidecar.exists()) check(savedSidecar.renameTo(originalSidecar)) {
                    "Galaxy database recovery could not restore a journal file."
                }
            }
        }

        if (!database.exists()) {
            if (temporary.exists()) check(temporary.delete()) {
                "Could not remove an incomplete encrypted staging database."
            }
            val key = keyStore.loadOrCreateDatabaseKey(databaseAlreadyExists = false)
            return encodePassphrase(key)
        }

        if (isPlaintextSQLite(database)) {
            val key = keyStore.loadOrCreateDatabaseKey(
                databaseAlreadyExists = true,
                allowExistingPlaintextDatabase = true
            )
            val passphrase = passphrase(key)
            key.fill(0)
            try {
                if (temporary.exists()) {
                    check(temporary.delete()) { "Could not remove an incomplete encrypted staging database." }
                }
                migratePlaintextDatabase(database, temporary, backup, passphrase)
            } finally {
            }
        }

        val key = keyStore.loadOrCreateDatabaseKey(databaseAlreadyExists = true)
        val passphrase = passphrase(key)
        key.fill(0)
        try {
            verifyEncryptedDatabase(database, passphrase, expectedVersion = null, expectedCounts = null)
            // Keep the plaintext rollback copy until Room has opened the encrypted database
            // and validated its own schema identity/migrations.
            return passphrase.toByteArray(Charsets.UTF_8)
        } catch (failure: Exception) {
            throw failure
        }
    }

    private fun migratePlaintextDatabase(
        sourceFile: File,
        encryptedTemporaryFile: File,
        plaintextBackupFile: File,
        passphrase: String
    ) {
        val source = SQLiteDatabase.openDatabase(
            sourceFile.absolutePath,
            byteArrayOf(),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
        val sourceVersion: Int
        val sourceCounts: Map<String, Long>
        try {
            checkIntegrity(source)
            sourceVersion = source.version
            sourceCounts = rowCounts(source)
            check(sourceCounts.keys == TABLES.toSet()) {
                "The existing Galaxy database does not contain the expected tables."
            }
            source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                check(cursor.moveToFirst()) { "Could not checkpoint the existing Galaxy database." }
                check(cursor.getInt(0) == 0) { "The existing Galaxy database is busy and cannot be migrated safely." }
            }
            source.rawExecSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?;",
                encryptedTemporaryFile.absolutePath,
                passphrase
            )
            try {
                source.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            } finally {
                source.rawExecSQL("DETACH DATABASE encrypted;")
            }
        } finally {
            source.close()
        }

        // Preserve Room's user_version: sqlcipher_export copies schema/data, not SQLite pragmas.
        val encrypted = SQLiteDatabase.openDatabase(
            encryptedTemporaryFile.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
        try {
            encrypted.version = sourceVersion
            verifyDatabaseContents(encrypted, sourceVersion, sourceCounts)
        } finally {
            encrypted.close()
        }

        val sidecars = listOf("-wal", "-shm", "-journal")
        val movedSidecars = mutableListOf<Pair<File, File>>()
        check(!plaintextBackupFile.exists()) { "A previous plaintext rollback copy needs recovery." }
        check(sourceFile.renameTo(plaintextBackupFile)) {
            "Could not preserve the original Galaxy database before promotion."
        }
        try {
            sidecars.forEach { suffix ->
                val originalSidecar = File(sourceFile.path + suffix)
                if (originalSidecar.exists()) {
                    val backupSidecar = File(plaintextBackupFile.path + suffix)
                    check(originalSidecar.renameTo(backupSidecar)) {
                        "Could not preserve a database journal file during migration."
                    }
                    movedSidecars += originalSidecar to backupSidecar
                }
            }
            check(encryptedTemporaryFile.renameTo(sourceFile)) {
                "Could not promote the verified encrypted Galaxy database."
            }
            verifyEncryptedDatabase(sourceFile, passphrase, sourceVersion, sourceCounts)
        } catch (failure: Exception) {
            // Never silently ignore a failed rollback step. The backup is the last known-good
            // copy; keep it in place unless every restoration operation succeeds.
            if (sourceFile.exists()) {
                check(sourceFile.delete()) {
                    "Migration failed and the incomplete encrypted database could not be removed."
                }
            }
            check(plaintextBackupFile.renameTo(sourceFile)) {
                "Migration failed and automatic rollback could not restore the original database."
            }
            movedSidecars.forEach { (original, saved) ->
                if (saved.exists()) check(saved.renameTo(original)) {
                    "Migration failed and an original database journal could not be restored."
                }
            }
            throw failure
        }

        // The rollback copy is intentionally retained until Room itself opens the new database.
    }

    fun completeMigration(context: Context) {
        val backup = File(context.getDatabasePath(DATABASE_NAME).parentFile, DATABASE_NAME + BACKUP_SUFFIX)
        if (backup.exists()) check(backup.delete()) {
            "Galaxy opened the encrypted database, but the temporary plaintext rollback copy could not be removed."
        }
        listOf("-wal", "-shm", "-journal").forEach { removeSidecar(backup.path + it) }
    }

    fun rollbackMigration(context: Context) {
        val database = context.getDatabasePath(DATABASE_NAME)
        val backup = File(database.parentFile, DATABASE_NAME + BACKUP_SUFFIX)
        if (!backup.exists()) return

        // Delete the failed encrypted database and its sidecars before restoring the
        // plaintext backup. Every operation is checked; a partial rollback must fail closed.
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val current = File(database.path + suffix)
            if (current.exists()) check(current.delete()) {
                "Could not remove the failed encrypted database during rollback."
            }
        }
        check(backup.renameTo(database)) {
            "Could not restore the original plaintext Galaxy database after Room validation failed."
        }
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val saved = File(backup.path + suffix)
            if (saved.exists()) check(saved.renameTo(File(database.path + suffix))) {
                "Could not restore an original Galaxy database journal during rollback."
            }
        }
    }

    private fun verifyEncryptedDatabase(
        file: File,
        passphrase: String,
        expectedVersion: Int?,
        expectedCounts: Map<String, Long>?
    ) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
        try {
            verifyDatabaseContents(database, expectedVersion, expectedCounts)
        } finally {
            database.close()
        }
    }

    private fun verifyDatabaseContents(
        database: SQLiteDatabase,
        expectedVersion: Int?,
        expectedCounts: Map<String, Long>?
    ) {
        checkIntegrity(database)
        if (expectedVersion != null) check(database.version == expectedVersion) {
            "Encrypted database schema version verification failed."
        }
        val actualCounts = rowCounts(database)
        check(actualCounts.keys == TABLES.toSet()) {
            "Encrypted database is missing one or more Galaxy data tables."
        }
        if (expectedCounts != null) check(actualCounts == expectedCounts) {
            "Encrypted database row counts do not match the original database."
        }
    }

    private fun checkIntegrity(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                "Galaxy database integrity verification failed."
            }
        }
    }

    private fun rowCounts(database: SQLiteDatabase): Map<String, Long> =
        TABLES.associateWith { table ->
            database.rawQuery("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor -> cursor.countFirst() }
        }

    private fun Cursor.countFirst(): Long {
        check(moveToFirst()) { "Could not verify Galaxy database table contents." }
        return getLong(0)
    }

    private fun isPlaintextSQLite(file: File): Boolean {
        val signature = ByteArray(SQLITE_HEADER.size)
        FileInputStream(file).use { input ->
            if (input.read(signature) != signature.size) return false
        }
        return MessageDigest.isEqual(signature, SQLITE_HEADER)
    }

    private fun encodePassphrase(key: ByteArray): ByteArray = try {
        passphrase(key).toByteArray(Charsets.UTF_8)
    } finally {
        key.fill(0)
    }

    private fun passphrase(key: ByteArray): String =
        Base64.encodeToString(key, Base64.NO_WRAP)

    private fun removeSidecar(path: String) {
        val file = File(path)
        if (file.exists()) check(file.delete()) { "Could not remove stale database journal file." }
    }

    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
