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
class GeneratedImageMigrationTest {
    @Test
    fun migrationFromVersion3CreatesMetadataTableAndIndexes() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
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
            GalaxyDatabaseFactory.MIGRATION_3_4.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(generated_images)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertEquals(
                setOf(
                    "assetId", "requestId", "conversationId", "characterId", "mimeType",
                    "width", "height", "byteSize", "sha256", "createdAtEpochMillis"
                ),
                columns
            )

            val indexes = mutableSetOf<String>()
            db.query("PRAGMA index_list(generated_images)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indexes += cursor.getString(nameIndex)
            }
            assertTrue(indexes.contains("index_generated_images_conversationId_createdAtEpochMillis"))
            assertTrue(indexes.contains("index_generated_images_characterId_createdAtEpochMillis"))
            assertTrue(indexes.contains("index_generated_images_requestId"))

            db.execSQL(
                """
                INSERT INTO generated_images (
                    assetId, requestId, conversationId, characterId, mimeType,
                    width, height, byteSize, sha256, createdAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf("asset-1", "request-1", "conversation-1", "character-1",
                    "image/png", 64, 64, 128L, "a".repeat(64), 1234L)
            )
            db.query("SELECT conversationId FROM generated_images WHERE assetId = ?", arrayOf("asset-1")).use {
                assertTrue(it.moveToFirst())
                assertEquals("conversation-1", it.getString(0))
            }
        } finally {
            helper.close()
        }
    }
}
