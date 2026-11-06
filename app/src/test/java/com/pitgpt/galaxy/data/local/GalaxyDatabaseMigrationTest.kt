package com.pitgpt.galaxy.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class GalaxyDatabaseMigrationTest {
    @Test
    fun migrationFromVersion1AddsAvatarUriWithoutLosingProfiles() {
        val context = RuntimeEnvironment.getApplication()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE character_profiles (
                                id TEXT NOT NULL PRIMARY KEY,
                                displayName TEXT NOT NULL,
                                description TEXT NOT NULL,
                                personaDefinition TEXT NOT NULL,
                                avatarId TEXT,
                                voiceId TEXT,
                                modelProviderId TEXT,
                                isBuiltIn INTEGER NOT NULL
                            )
                            """.trimIndent()
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
            db.execSQL(
                """
                INSERT INTO character_profiles (
                    id, displayName, description, personaDefinition,
                    avatarId, voiceId, modelProviderId, isBuiltIn
                ) VALUES ('galaxy.default', 'Галактика', 'Описание', 'Инструкции',
                    'avatar.cosmic', 'voice.feminine_warm', NULL, 1)
                """.trimIndent()
            )

            GalaxyDatabaseFactory.MIGRATION_1_2.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(character_profiles)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue(columns.contains("avatarImageUri"))

            db.query(
                "SELECT id, displayName, avatarImageUri FROM character_profiles WHERE id = ?",
                arrayOf("galaxy.default")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("galaxy.default", cursor.getString(0))
                assertEquals("Галактика", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
        } finally {
            helper.close()
        }
    }
}
