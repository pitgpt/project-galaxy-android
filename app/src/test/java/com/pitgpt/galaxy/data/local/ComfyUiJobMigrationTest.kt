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

    @Test
    fun migrationFromVersionFiveCreatesIndexedGenerationRequestTable() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE comfyui_jobs (promptId TEXT NOT NULL PRIMARY KEY, requestId TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL, cancelled INTEGER NOT NULL, submissionStarted INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO comfyui_jobs (promptId, requestId, createdAtEpochMillis, cancelled, submissionStarted) VALUES ('existing-prompt', 'existing-request', 777, 0, 1)"
                        )
                    }
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
            GalaxyDatabaseFactory.MIGRATION_5_6.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(image_generation_requests)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertEquals(
                setOf(
                    "requestId", "conversationId", "characterId", "prompt", "negativePrompt",
                    "preferredMimeType", "workflowId", "width", "height", "seed",
                    "referenceAssetIdsJson", "createdAtEpochMillis"
                ),
                columns
            )

            val indexes = mutableSetOf<String>()
            db.query("PRAGMA index_list(image_generation_requests)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indexes += cursor.getString(nameIndex)
            }
            assertTrue(indexes.contains("index_image_generation_requests_conversationId_createdAtEpochMillis"))
            assertTrue(indexes.contains("index_image_generation_requests_characterId_createdAtEpochMillis"))

            db.query(
                "SELECT requestId, submissionStarted FROM comfyui_jobs WHERE promptId = ?",
                arrayOf("existing-prompt")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing-request", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }

            db.execSQL(
                """
                INSERT INTO image_generation_requests (
                    requestId, conversationId, characterId, prompt, negativePrompt,
                    preferredMimeType, workflowId, width, height, seed,
                    referenceAssetIdsJson, createdAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf("request-1", "conversation-1", "character-1", "secret prompt", null,
                    "image/png", "workflow-v1", 512, 768, null, "[]", 1234L)
            )
            db.query(
                "SELECT prompt, width, height FROM image_generation_requests WHERE requestId = ?",
                arrayOf("request-1")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("secret prompt", cursor.getString(0))
                assertEquals(512, cursor.getInt(1))
                assertEquals(768, cursor.getInt(2))
            }
        } finally {
            helper.close()
        }
    }

}
