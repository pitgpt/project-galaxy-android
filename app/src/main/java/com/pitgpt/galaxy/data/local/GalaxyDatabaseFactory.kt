package com.pitgpt.galaxy.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object GalaxyDatabaseFactory {
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE character_profiles ADD COLUMN avatarImageUri TEXT")
        }
    }

    private val encryptor = PlaintextDatabaseEncryptor()

    @Volatile
    private var instance: GalaxyDatabase? = null

    fun get(context: Context): GalaxyDatabase =
        instance ?: synchronized(this) {
            instance ?: openEncryptedDatabase(context.applicationContext).also { instance = it }
        }

    private fun openEncryptedDatabase(context: Context): GalaxyDatabase {
        System.loadLibrary("sqlcipher")
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        check(databaseFile.parentFile?.exists() == true || databaseFile.parentFile?.mkdirs() == true) {
            "Could not create the Galaxy database directory."
        }

        val databaseExists = databaseFile.exists()
        val databaseIsPlaintext = encryptor.isPlaintextDatabase(databaseFile)
        val encryptedTemporaryExists = File(
            databaseFile.parentFile,
            "${databaseFile.name}.encrypted-tmp"
        ).exists()
        val databaseKey = EncryptedDatabaseKeyStore(context)
            .getOrCreateDatabaseKey(databaseExists, databaseIsPlaintext, encryptedTemporaryExists)
        val pendingMigration = try {
            encryptor.migrateIfNeeded(databaseFile, databaseKey)
        } catch (failure: Exception) {
            databaseKey.fill(0)
            throw IllegalStateException(
                "Galaxy could not safely encrypt its local database. The original data has been preserved.",
                failure
            )
        }

        // Encode directly to bytes so an immutable passphrase String is not left on the heap.
        val passphraseBytes = Base64.encode(databaseKey, Base64.NO_WRAP)
        databaseKey.fill(0)
        val database = Room.databaseBuilder(
            context,
            GalaxyDatabase::class.java,
            DATABASE_NAME
        )
            // SQLCipher's helper retains this byte array for the lifetime of the helper so it can
            // reopen the database. Do not zero it after the first open; clear it on failed setup.
            .openHelperFactory(SupportOpenHelperFactory(passphraseBytes, null, true))
            .addMigrations(MIGRATION_1_2)
            .build()

        try {
            // Room opens lazily. Force the encrypted connection before retiring the plaintext backup.
            database.openHelper.writableDatabase
            encryptor.complete(pendingMigration)
            return database
        } catch (failure: Exception) {
            runCatching { database.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { encryptor.rollback(pendingMigration) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            passphraseBytes.fill(0)
            throw IllegalStateException(
                "Galaxy could not open its encrypted local database. The original data was retained where possible; inspect suppressed recovery errors before retrying.",
                failure
            )
        }
    }

    private const val DATABASE_NAME = "galaxy.db"
}
