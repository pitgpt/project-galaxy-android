package com.pitgpt.galaxy.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ComfyUiJobMigrationTest {
    @Test
    fun migrationFromVersion2CreatesIndexedJobTable() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            GalaxyDatabaseFactory.MIGRATION_2_3.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(comfyui_jobs)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertEquals(setOf("promptId", "requestId", "createdAtEpochMillis", "cancelled"), columns)

            val indexes = mutableSetOf<String>()
            db.query("PRAGMA index_list(comfyui_jobs)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indexes += cursor.getString(nameIndex)
            }
            assertTrue(indexes.contains("index_comfyui_jobs_requestId"))

            db.execSQL(
                "INSERT INTO comfyui_jobs (promptId, requestId, createdAtEpochMillis, cancelled) VALUES (?, ?, ?, ?)",
                arrayOf("prompt-1", "request-1", 1234L, 0)
            )
            db.query("SELECT requestId, cancelled FROM comfyui_jobs WHERE promptId = ?", arrayOf("prompt-1")).use {
                assertTrue(it.moveToFirst())
                assertEquals("request-1", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
        } finally {
            helper.close()
        }
    }
    @Test
    fun migrationFromVersionFourAddsSubmissionStartedWithFalseDefault() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE comfyui_jobs (promptId TEXT NOT NULL PRIMARY KEY, requestId TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL, cancelled INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO comfyui_jobs (promptId, requestId, createdAtEpochMillis, cancelled) VALUES ('prompt-1', 'request-1', 1234, 0)"
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            GalaxyDatabaseFactory.MIGRATION_4_5.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(comfyui_jobs)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("submissionStarted"))

            db.query(
                "SELECT submissionStarted FROM comfyui_jobs WHERE promptId = ?",
                arrayOf("prompt-1")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            helper.close()
        }
    }

}
