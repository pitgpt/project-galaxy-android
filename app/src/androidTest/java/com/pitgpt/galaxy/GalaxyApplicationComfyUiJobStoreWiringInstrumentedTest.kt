package com.pitgpt.galaxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiJobRecord
import com.pitgpt.galaxy.data.providers.comfyui.RoomComfyUiJobStore
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalaxyApplicationComfyUiJobStoreWiringInstrumentedTest {
    @Test
    fun productionApplicationUsesRoomJobStoreOnEncryptedDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as GalaxyApplication
        val jobStore = application.comfyUiJobStore

        assertTrue(
            "The production composition root must bind ComfyUiJobStore to RoomComfyUiJobStore",
            jobStore is RoomComfyUiJobStore
        )

        val databaseFile = context.getDatabasePath("galaxy.db")
        assertTrue("Opening the production job store must create the Galaxy database", databaseFile.isFile)

        val plaintextSqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val actualHeader = ByteArray(plaintextSqliteHeader.size)
        FileInputStream(databaseFile).use { input ->
            var offset = 0
            while (offset < actualHeader.size) {
                val count = input.read(actualHeader, offset, actualHeader.size - offset)
                assertTrue("The Galaxy database header must be readable", count > 0)
                offset += count
            }
        }
        assertFalse(
            "The production Galaxy database must not expose the plaintext SQLite header",
            actualHeader.contentEquals(plaintextSqliteHeader)
        )

        val promptId = "galaxy-di-${UUID.randomUUID()}"
        val record = ComfyUiJobRecord(
            promptId = promptId,
            requestId = "galaxy-di-request-${UUID.randomUUID()}",
            createdAtEpochMillis = 1_794_231_000_000L
        )
        var inserted = false
        try {
            jobStore.put(record)
            inserted = true

            assertEquals(record, jobStore.get(promptId))
            assertTrue("The production SQLCipher-backed store must commit the submission claim", jobStore.markSubmissionStarted(promptId))
            assertEquals(record.copy(submissionStarted = true), jobStore.get(promptId))
        } finally {
            if (inserted) jobStore.delete(promptId)
        }

        assertNull("The temporary wiring-test record must be removed", jobStore.get(promptId))
    }
}
