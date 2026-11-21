package com.pitgpt.galaxy.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

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

        val encryptedTemporaryFile = File(
            databaseFile.parentFile,
            databaseFile.name + ENCRYPTED_TEMPORARY_SUFFIX
        )
        val keyStore = EncryptedDatabaseKeyStore(context)
        val databaseKey = keyStore.getOrCreateDatabaseKey(
            databaseExists = databaseFile.exists(),
            databaseIsPlaintext = encryptor.isPlaintextDatabase(databaseFile),
            encryptedTemporaryExists = encryptedTemporaryFile.exists()
        )
        val pendingMigration = try {
            encryptor.migrateIfNeeded(databaseFile, databaseKey)
        } catch (failure: Exception) {
            databaseKey.fill(0)
            throw IllegalStateException(
                "Galaxy could not safely encrypt its local database. The original data has been preserved.",
                failure
            )
        }
        databaseKey.fill(0)

        val passphraseProvider = {
            val key = keyStore.getOrCreateDatabaseKey(
                databaseExists = databaseFile.exists(),
                databaseIsPlaintext = encryptor.isPlaintextDatabase(databaseFile),
                encryptedTemporaryExists = encryptedTemporaryFile.exists()
            )
            try {
                Base64.encode(key, Base64.NO_WRAP)
            } finally {
                key.fill(0)
            }
        }

        return try {
            openDatabaseWithMigrationRecovery(
                buildDatabase = {
                    Room.databaseBuilder(
                        context,
                        GalaxyDatabase::class.java,
                        DATABASE_NAME
                    )
                        .openHelperFactory(ZeroizingSqlCipherOpenHelperFactory(passphraseProvider))
                        .addMigrations(MIGRATION_1_2)
                        .build()
                },
                openDatabase = { database -> database.openHelper.writableDatabase },
                completeMigration = { encryptor.complete(pendingMigration) },
                closeDatabase = { database -> database.close() },
                rollbackMigration = { encryptor.rollback(pendingMigration) }
            )
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Galaxy could not open its encrypted local database. The original data was retained where possible; inspect suppressed recovery errors before retrying.",
                failure
            )
        }
    }

    private const val DATABASE_NAME = "galaxy.db"
    private const val ENCRYPTED_TEMPORARY_SUFFIX = ".encrypted-tmp"
}
