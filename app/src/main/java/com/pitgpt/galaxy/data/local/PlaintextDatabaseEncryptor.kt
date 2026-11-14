package com.pitgpt.galaxy.data.local

import android.database.Cursor
import android.util.Base64
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Converts the existing Room SQLite database to SQLCipher without ever overwriting the only
 * plaintext copy before the encrypted copy has passed integrity and row-count verification.
 *
 * The plaintext rollback copy is retained until Room has successfully opened the encrypted DB.
 */
internal class PlaintextDatabaseEncryptor {
    data class PendingMigration internal constructor(
        internal val original: File,
        internal val backup: File,
        internal val expectedSnapshot: DatabaseSnapshot
    )

    internal data class DatabaseSnapshot(
        val rowCounts: Map<String, Long>,
        val userVersion: Int,
        val applicationId: Int,
        val contentDigest: String
    )

    fun isPlaintextDatabase(database: File): Boolean = hasSqliteHeader(database)

    fun migrateIfNeeded(database: File, databaseKey: ByteArray): PendingMigration? {
        val backup = File(database.parentFile, "${database.name}.plaintext-backup")
        val temporary = File(database.parentFile, "${database.name}.encrypted-tmp")
        val plaintext = hasSqliteHeader(database)

        // Recover sidecars if the process stopped after moving them but before renaming the DB.
        if (database.exists() && plaintext && !backup.exists()) {
            restoreSidecar(sidecar(backup, "-wal"), sidecar(database, "-wal"))
            restoreSidecar(sidecar(backup, "-shm"), sidecar(database, "-shm"))
        }

        if (backup.exists() && !database.exists()) {
            if (temporary.exists()) {
                val expectedSnapshot = readPlaintextSnapshot(backup)
                verifyEncrypted(temporary, databaseKey, expectedSnapshot)
                check(temporary.renameTo(database)) { "Could not promote verified encrypted database." }
                return PendingMigration(database, backup, expectedSnapshot)
            }
            readPlaintextSnapshot(backup)
            check(backup.renameTo(database)) { "Could not restore the original Galaxy database." }
            return migrateIfNeeded(database, databaseKey)
        }

        if (database.exists() && !plaintext) {
            val snapshot = if (backup.exists()) readPlaintextSnapshot(backup) else null
            verifyEncrypted(database, databaseKey, snapshot)
            if (backup.exists()) return PendingMigration(database, backup, checkNotNull(snapshot))
            sidecar(backup, "-wal").delete()
            sidecar(backup, "-shm").delete()
            return null
        }

        if (!database.exists()) return null
        check(!backup.exists()) {
            "A previous Galaxy database migration needs recovery. Original data was preserved."
        }

        if (temporary.exists()) {
            check(temporary.delete()) { "Could not remove an incomplete encrypted migration file." }
        }

        val expectedSnapshot = exportPlaintext(database, temporary, databaseKey)
        verifyEncrypted(temporary, databaseKey, expectedSnapshot)

        val backupWal = sidecar(backup, "-wal")
        val backupShm = sidecar(backup, "-shm")
        try {
            moveSidecar(sidecar(database, "-wal"), backupWal)
            moveSidecar(sidecar(database, "-shm"), backupShm)
        } catch (failure: Exception) {
            restoreSidecar(backupWal, sidecar(database, "-wal"))
            restoreSidecar(backupShm, sidecar(database, "-shm"))
            throw failure
        }

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

        return PendingMigration(database, backup, expectedSnapshot)
    }

    fun complete(pending: PendingMigration?) {
        if (pending == null) return
        listOf(sidecar(pending.backup, "-wal"), sidecar(pending.backup, "-shm")).forEach { file ->
            check(!file.exists() || file.delete()) {
                "Could not remove a plaintext Galaxy database sidecar."
            }
        }
        check(pending.backup.delete()) {
            "The encrypted Galaxy database is active, but its plaintext rollback copy could not be removed."
        }
    }

    fun rollback(pending: PendingMigration?) {
        if (pending == null) return
        val failedEncrypted = File(pending.original.parentFile, "${pending.original.name}.encrypted-failed")
        val failedWal = sidecar(failedEncrypted, "-wal")
        val failedShm = sidecar(failedEncrypted, "-shm")
        check(!failedEncrypted.exists() && !failedWal.exists() && !failedShm.exists()) {
            "A previous failed encrypted database candidate or sidecar exists; refusing to overwrite it during rollback."
        }
        val backupSnapshot = readPlaintextSnapshot(pending.backup)
        check(backupSnapshot == pending.expectedSnapshot) {
            "The plaintext rollback copy no longer matches the verified migration snapshot; refusing rollback."
        }
        if (pending.original.exists()) {
            check(pending.original.renameTo(failedEncrypted)) {
                "Could not preserve the failed encrypted database during rollback."
            }
            moveSidecar(sidecar(pending.original, "-wal"), sidecar(failedEncrypted, "-wal"))
            moveSidecar(sidecar(pending.original, "-shm"), sidecar(failedEncrypted, "-shm"))
        }
        check(pending.backup.renameTo(pending.original)) {
            "Could not restore the original plaintext Galaxy database."
        }
        restoreSidecar(sidecar(pending.backup, "-wal"), sidecar(pending.original, "-wal"))
        restoreSidecar(sidecar(pending.backup, "-shm"), sidecar(pending.original, "-shm"))
        failedEncrypted.delete()
        sidecar(failedEncrypted, "-wal").delete()
        sidecar(failedEncrypted, "-shm").delete()
    }

    private fun exportPlaintext(sourceFile: File, encryptedFile: File, databaseKey: ByteArray): DatabaseSnapshot {
        val source = SQLiteDatabase.openDatabase(
            sourceFile.absolutePath,
            null,
            // SQLCipher applies this flag to ATTACH targets too, allowing the encrypted temp DB to be created.
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        )
        try {
            checkpoint(source)
            val snapshot = readSnapshot(source)
            val passphrase = Base64.encodeToString(databaseKey, Base64.NO_WRAP)
            source.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?;",
                arrayOf(encryptedFile.absolutePath, passphrase)
            )
            try {
                source.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                source.rawExecSQL("PRAGMA encrypted.user_version = ${snapshot.userVersion};")
                source.rawExecSQL("PRAGMA encrypted.application_id = ${snapshot.applicationId};")
            } finally {
                source.execSQL("DETACH DATABASE encrypted;")
            }
            return snapshot
        } finally {
            source.close()
        }
    }

    private fun readPlaintextSnapshot(file: File): DatabaseSnapshot {
        check(file.isFile && file.length() > 0L) {
            "The plaintext Galaxy rollback copy is missing or empty."
        }
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        return try {
            verifyIntegrity(database, "Plaintext Galaxy rollback copy")
            checkpoint(database)
            readSnapshot(database)
        } finally {
            database.close()
        }
    }

    private fun verifyEncrypted(
        file: File,
        databaseKey: ByteArray,
        expectedSnapshot: DatabaseSnapshot?
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
            verifyIntegrity(database, "Encrypted Galaxy database")
            expectedSnapshot?.let { expected ->
                check(readSnapshot(database) == expected) {
                    "Encrypted Galaxy database metadata or contents do not match the original database."
                }
            }
        } finally {
            database.close()
        }
    }

    private fun verifyIntegrity(database: SQLiteDatabase, description: String) {
        database.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
            check(cursor.moveToFirst()) {
                "$description integrity check returned no result."
            }
            check(
                cursor.getString(0).equals("ok", ignoreCase = true) && !cursor.moveToNext()
            ) {
                "$description failed its integrity check."
            }
        }
    }

    private fun checkpoint(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA wal_checkpoint(FULL);", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Could not checkpoint the Galaxy database before encryption."
            }
        }
    }

    private fun readSnapshot(database: SQLiteDatabase): DatabaseSnapshot {
        val rowCounts = readRowCounts(database)
        return DatabaseSnapshot(
            rowCounts = rowCounts,
            userVersion = readPragmaInt(database, "user_version"),
            applicationId = readPragmaInt(database, "application_id"),
            contentDigest = readContentDigest(database, rowCounts.keys)
        )
    }

    private fun readPragmaInt(database: SQLiteDatabase, pragma: String): Int =
        database.rawQuery("PRAGMA $pragma;", null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun readRowCounts(database: SQLiteDatabase): Map<String, Long> {
        val tables = mutableListOf<String>()
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND (name NOT LIKE 'sqlite_%' OR name = 'sqlite_sequence') ORDER BY name COLLATE BINARY;",
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

    /**
     * Hashes schema definitions and every stored row using a canonical, typed encoding.
     * Rows are ordered by all columns with explicit BINARY collation so export/reopen does not
     * depend on rowid allocation or a table's declared text collation.
     */
    private fun readContentDigest(database: SQLiteDatabase, tables: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateString(digest, "galaxy-sqlcipher-snapshot-v1")

        database.rawQuery(
            """
            SELECT type, name, tbl_name, sql
            FROM sqlite_master
            WHERE name NOT LIKE 'sqlite_%' OR name = 'sqlite_sequence'
            ORDER BY type COLLATE BINARY, name COLLATE BINARY;
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                digest.update(0x53.toByte())
                for (index in 0 until cursor.columnCount) {
                    updateNullableString(
                        digest,
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    )
                }
            }
        }

        tables.sorted().forEach { table ->
            val columns = readColumnNames(database, table)
            val quotedTable = quoteIdentifier(table)
            val orderBy = columns.joinToString(", ") { column ->
                val quotedColumn = quoteIdentifier(column)
                "typeof(" + quotedColumn + ") COLLATE BINARY, " + quotedColumn + " COLLATE BINARY"
            }

            digest.update(0x54.toByte())
            updateString(digest, table)
            updateInt(digest, columns.size)
            columns.forEach { updateString(digest, it) }

            database.rawQuery(
                "SELECT * FROM " + quotedTable + " ORDER BY " + orderBy + ";",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    digest.update(0x52.toByte())
                    for (index in 0 until cursor.columnCount) {
                        updateCursorValue(digest, cursor, index)
                    }
                    digest.update(0x45.toByte())
                }
            }
            digest.update(0x74.toByte())
        }

        return digest.digest().toHex()
    }

    private fun readColumnNames(database: SQLiteDatabase, table: String): List<String> {
        val columns = mutableListOf<String>()
        database.rawQuery(
            "PRAGMA table_info(" + quoteIdentifier(table) + ");",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        check(columns.isNotEmpty()) {
            "Could not read columns for a Galaxy database snapshot table."
        }
        return columns
    }

    private fun quoteIdentifier(identifier: String): String =
        "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun updateCursorValue(digest: MessageDigest, cursor: Cursor, index: Int) {
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> digest.update(0.toByte())
            Cursor.FIELD_TYPE_INTEGER -> {
                digest.update(1.toByte())
                updateLong(digest, cursor.getLong(index))
            }
            Cursor.FIELD_TYPE_FLOAT -> {
                digest.update(2.toByte())
                // SQLite sorts -0.0 and +0.0 as equal; normalize zero so tie order cannot
                // make the digest depend on insertion order or hidden rowid.
                val value = cursor.getDouble(index)
                val canonicalValue = if (value == 0.0) 0.0 else value
                updateLong(digest, java.lang.Double.doubleToLongBits(canonicalValue))
            }
            Cursor.FIELD_TYPE_STRING -> {
                digest.update(3.toByte())
                updateBytes(digest, cursor.getString(index).toByteArray(Charsets.UTF_8))
            }
            Cursor.FIELD_TYPE_BLOB -> {
                digest.update(4.toByte())
                updateBytes(digest, cursor.getBlob(index))
            }
            else -> error("Unsupported SQLite value type in a Galaxy database snapshot.")
        }
    }

    private fun updateNullableString(digest: MessageDigest, value: String?) {
        if (value == null) {
            digest.update(0.toByte())
        } else {
            digest.update(1.toByte())
            updateString(digest, value)
        }
    }

    private fun updateString(digest: MessageDigest, value: String) =
        updateBytes(digest, value.toByteArray(Charsets.UTF_8))

    private fun updateBytes(digest: MessageDigest, value: ByteArray) {
        updateInt(digest, value.size)
        digest.update(value)
    }

    private fun updateInt(digest: MessageDigest, value: Int) {
        digest.update(ByteBuffer.allocate(4).putInt(value).array())
    }

    private fun updateLong(digest: MessageDigest, value: Long) {
        digest.update(ByteBuffer.allocate(8).putLong(value).array())
    }

    private fun ByteArray.toHex(): String {
        val alphabet = "0123456789abcdef"
        return buildString(size * 2) {
            for (byte in this@toHex) {
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
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
