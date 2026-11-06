package com.pitgpt.galaxy.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object GalaxyDatabaseFactory {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE character_profiles ADD COLUMN avatarImageUri TEXT")
        }
    }

    @Volatile
    private var instance: GalaxyDatabase? = null

    fun get(context: Context): GalaxyDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GalaxyDatabase::class.java,
                "galaxy.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
}
