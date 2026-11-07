package com.pitgpt.galaxy.data.local

import android.content.Context
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
        val databaseKey = EncryptedDatabaseKeyStore(context)
            .getOrCreateDatabaseKey(databaseExists, databaseIsPlaintext)
        val pendingMigration = try {
            encryptor.migrateIfNeeded(databaseFile, databaseKey)
        } catch (failure: Exception) {
            databaseKey.fill(0)
            throw IllegalStateException(
                "Galaxy could not safely encrypt its local database. The original data has been preserved.",
                failure
            )
        }

        val passphrase = android.util.Base64.encodeToString(databaseKey, android.util.Base64.NO_WRAP)
        databaseKey.fill(0)
        val passphraseBytes = passphrase.toByteArray(Charsets.UTF_8)
        val database = Room.databaseBuilder(
            context,
            GalaxyDatabase::class.java,
            DATABASE_NAME
        )
            .openHelperFactory(SupportOpenHelperFactory(passphraseBytes, null, true))
            .addMigrations(MIGRATION_1_2)
            .build()

        try {
            // Room opens lazily. Force the encrypted connection before retiring the plaintext backup.
            database.openHelper.writableDatabase
            encryptor.complete(pendingMigration)
            return database
        } catch (failure: Exception) {
            runCatching { database.close() }
            runCatching { encryptor.rollback(pendingMigration) }
            passphraseBytes.fill(0)
            throw IllegalStateException(
                "Galaxy could not open its encrypted local database. The original data was retained where possible.",
                failure
            )
        }
    }

    private const val DATABASE_NAME = "galaxy.db"
}
