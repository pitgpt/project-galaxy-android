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

    @Volatile
    private var instance: GalaxyDatabase? = null

    // Retain the passphrase for the lifetime of the Room helper: SQLCipher may need it
    // if the helper closes and reopens its connection later in the process.
    @Volatile
    private var databasePassphrase: ByteArray? = null

    fun get(context: Context): GalaxyDatabase =
        instance ?: synchronized(this) {
            instance ?: run {
                val passphrase = EncryptedDatabaseBootstrap.prepare(context)
                try {
                    Room.databaseBuilder(
                        context.applicationContext,
                        GalaxyDatabase::class.java,
                        "galaxy.db"
                    )
                        .openHelperFactory(SupportOpenHelperFactory(passphrase))
                        .addMigrations(MIGRATION_1_2)
                        .build()
                        .also {
                            databasePassphrase = passphrase
                            instance = it
                        }
                } catch (failure: Exception) {
                    passphrase.fill(0)
                    throw failure
                }
            }
        }
}
