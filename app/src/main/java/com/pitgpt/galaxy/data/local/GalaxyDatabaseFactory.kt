package com.pitgpt.galaxy.data.local

import java.io.File

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

    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS comfyui_jobs (
                    promptId TEXT NOT NULL PRIMARY KEY,
                    requestId TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL,
                    cancelled INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_comfyui_jobs_requestId ON comfyui_jobs(requestId)"
            )
        }
    }

    internal val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS generated_images (
                    assetId TEXT NOT NULL PRIMARY KEY,
                    requestId TEXT NOT NULL,
                    conversationId TEXT NOT NULL,
                    characterId TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    byteSize INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_generated_images_conversationId_createdAtEpochMillis ON generated_images(conversationId, createdAtEpochMillis)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_generated_images_characterId_createdAtEpochMillis ON generated_images(characterId, createdAtEpochMillis)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_generated_images_requestId ON generated_images(requestId)"
            )
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

        val passphraseBytes = Base64.encode(databaseKey, Base64.NO_WRAP)
        databaseKey.fill(0)
        val database = Room.databaseBuilder(
            context,
            GalaxyDatabase::class.java,
            DATABASE_NAME
        )
            .openHelperFactory(SupportOpenHelperFactory(passphraseBytes, null, true))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

        try {
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
