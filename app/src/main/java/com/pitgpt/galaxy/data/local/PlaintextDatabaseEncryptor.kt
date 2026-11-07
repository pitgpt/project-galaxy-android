package com.pitgpt.galaxy.data.local

import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Converts the existing Room SQLite database to SQLCipher without ever overwriting the only
 * plaintext copy before the encrypted copy has passed integrity and row-count verification.
 *
 * The plaintext rollback copy is retained until Room has successfully opened the encrypted DB.
 */
internal class PlaintextDatabaseEncryptor {
    data class PendingMigration internal constructor(
        internal val original: File,
        internal val backup: File
    )

    fun migrateIfNeeded(database: File, databaseKey: ByteArray): PendingMigration? {
        val backup = File(database.parentFile, "${database.name}.plaintext-backup")
        val temporary = File(database.parentFile, "${database.name}.encrypted-tmp")
        val plaintext = hasSqliteHeader(database)

        if (backup.exists() && !database.exists()) {
            if (temporary.exists()) {
                verifyEncrypted(temporary, databaseKey, readPlaintextRowCounts(backup))
                check(temporary.renameTo(database)) { "Could not promote verified encrypted database." }
                return PendingMigration(database, backup)
            }
            check(backup.renameTo(database)) { "Could not restore the original Galaxy database." }
            return migrateIfNeeded(database, databaseKey)
        }

        if (database.exists() && !plaintext) {
            verifyEncrypted(database, databaseKey, null)
            return if (backup.exists()) PendingMigration(database, backup) else null
        }

        if (!database.exists()) return null
        check(!backup.exists()) {
            "A previous Galaxy database migration needs recovery. Original data was preserved."
        }

        if (temporary.exists()) {
            check(temporary.delete()) { "Could not remove an incomplete encrypted migration file." }
        }

        val expectedCounts = exportPlaintext(database, temporary, databaseKey)
        verifyEncrypted(temporary, databaseKey, expectedCounts)

        val backupWal = sidecar(backup, "-wal")
        val backupShm = sidecar(backup, "-shm")
        moveSidecar(sidecar(database, "-wal"), backupWal)
        moveSidecar(sidecar(database, "-shm"), backupShm)

        if (!database.renameTo(backup)) {
            restoreSidecar(backupWal, sidecar(database, "-wal"))
            restoreSidecar(backupShm, sidecar(database, "-shm"))
            error("Could not preserve the original Galaxy database before promotion.")
        }

        if (!temporary.renameTo(database)) {
            check(backup.renameTo(database)) {
                "Encrypted database promotion failed and the original database needs manual recovery."
            }
            restoreSidecar(backupWal, sidecar(database, "-wal"))
            restoreSidecar(backupShm, sidecar(database, "-shm"))
            error("Could not promote the encrypted Galaxy database. Original data was restored.")
        }

        return PendingMigration(database, backup)
    }

    fun complete(pending: PendingMigration?) {
        if (pending == null) return
        check(pending.backup.delete()) {
            "The encrypted Galaxy database is active, but its plaintext rollback copy could not be removed."
        }
        sidecar(pending.backup, "-wal").delete()
        sidecar(pending.backup, "-shm").delete()
    }

    fun rollback(pending: PendingMigration?) {
        if (pending == null) return
        val failedEncrypted = File(pending.original.parentFile, "${pending.original.name}.encrypted-failed")
        if (failedEncrypted.exists()) check(failedEncrypted.delete())
        if (pending.original.exists()) {
            check(pending.original.renameTo(failedEncrypted)) {
                "Could not preserve the failed encrypted database during rollback."
            }
        }
        check(pending.backup.renameTo(pending.original)) {
            "Could not restore the original plaintext Galaxy database."
        }
        restoreSidecar(sidecar(pending.backup, "-wal"), sidecar(pending.original, "-wal"))
        restoreSidecar(sidecar(pending.backup, "-shm"), sidecar(pending.original, "-shm"))
        failedEncrypted.delete()
    }

    private fun exportPlaintext(sourceFile: File, encryptedFile: File, databaseKey: ByteArray): Map<String, Long> {
        val source = SQLiteDatabase.openDatabase(
            sourceFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            source.rawExecSQL("PRAGMA wal_checkpoint(FULL);")
            val rowCounts = readRowCounts(source)
            val passphrase = Base64.encodeToString(databaseKey, Base64.NO_WRAP)
            source.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?;",
                arrayOf(encryptedFile.absolutePath, passphrase)
            )
            try {
                source.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            } finally {
                source.execSQL("DETACH DATABASE encrypted;")
            }
            return rowCounts
        } finally {
            source.close()
        }
    }

    private fun readPlaintextRowCounts(file: File): Map<String, Long> {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        return try {
            database.rawExecSQL("PRAGMA wal_checkpoint(FULL);")
            readRowCounts(database)
        } finally {
            database.close()
        }
    }

    private fun verifyEncrypted(
        file: File,
        databaseKey: ByteArray,
        expectedCounts: Map<String, Long>?
    ) {
        val passphrase = Base64.encodeToString(databaseKey, Base64.NO_WRAP)
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null
        )
        try {
            database.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "Encrypted Galaxy database failed its integrity check."
                }
            }
            expectedCounts?.let { expected ->
                check(readRowCounts(database) == expected) {
                    "Encrypted Galaxy database row counts do not match the original database."
                }
            }
        } finally {
            database.close()
        }
    }

    private fun readRowCounts(database: SQLiteDatabase): Map<String, Long> {
        val tables = mutableListOf<String>()
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%';",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        return tables.associateWith { table ->
            val safeName = table.replace("\"", "\"\"")
            database.rawQuery("SELECT COUNT(*) FROM \"$safeName\";", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
    }

    private fun hasSqliteHeader(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_HEADER.size) return false
        return file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
        }
    }

    private fun sidecar(database: File, suffix: String) = File(database.parentFile, database.name + suffix)

    private fun moveSidecar(source: File, destination: File) {
        if (source.exists()) {
            check(source.renameTo(destination)) { "Could not preserve a Galaxy database sidecar." }
        }
    }

    private fun restoreSidecar(source: File, destination: File) {
        if (source.exists()) check(source.renameTo(destination))
    }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
