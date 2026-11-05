package com.pitgpt.galaxy.data.local

import android.content.Context
import androidx.room.Room

object GalaxyDatabaseFactory {
    @Volatile
    private var instance: GalaxyDatabase? = null

    fun get(context: Context): GalaxyDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GalaxyDatabase::class.java,
                "galaxy.db"
            ).build().also { instance = it }
        }
}
