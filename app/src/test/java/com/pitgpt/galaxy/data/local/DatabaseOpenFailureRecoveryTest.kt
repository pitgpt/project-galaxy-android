package com.pitgpt.galaxy.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseOpenFailureRecoveryTest {
    private data class FakeDatabase(val id: String)

    @Test
    fun keepsOpenFailurePrimaryAndRecordsCloseThenRollbackFailures() {
        val database = FakeDatabase("partial")
        val openFailure = IllegalStateException("database open failed")
        val closeFailure = IllegalArgumentException("database close failed")
        val rollbackFailure = UnsupportedOperationException("migration rollback failed")
        var rollbackAttempted = false

        val actualFailure = runCatching {
            openDatabaseWithMigrationRecovery(
                buildDatabase = { database },
                openDatabase = { throw openFailure },
                completeMigration = { error("Migration must not complete after an open failure") },
                closeDatabase = { throw closeFailure },
                rollbackMigration = {
                    rollbackAttempted = true
                    throw rollbackFailure
                }
            )
        }.exceptionOrNull()

        assertSame(openFailure, actualFailure)
        assertTrue("Rollback must still run when database close fails", rollbackAttempted)
        assertEquals("Both recovery failures must be retained", 2, openFailure.suppressed.size)
        assertSame("Close failure must be recorded first", closeFailure, openFailure.suppressed[0])
        assertSame("Rollback failure must be recorded second", rollbackFailure, openFailure.suppressed[1])
    }

    @Test
    fun avoidsSelfSuppressionAndStillRunsRollback() {
        val database = FakeDatabase("partial")
        val openFailure = IllegalStateException("database open failed")
        val rollbackFailure = IllegalStateException("migration rollback failed")
        var rollbackAttempted = false

        val actualFailure = runCatching {
            openDatabaseWithMigrationRecovery(
                buildDatabase = { database },
                openDatabase = { throw openFailure },
                completeMigration = {},
                closeDatabase = { throw openFailure },
                rollbackMigration = {
                    rollbackAttempted = true
                    throw rollbackFailure
                }
            )
        }.exceptionOrNull()

        assertSame(openFailure, actualFailure)
        assertTrue("Rollback must run after a close callback rethrows the primary failure", rollbackAttempted)
        assertEquals("The primary failure must not be suppressed onto itself", 1, openFailure.suppressed.size)
        assertSame(rollbackFailure, openFailure.suppressed.single())
    }

    @Test
    fun recordsRollbackFailureWhenDatabaseCloseSucceeds() {
        val database = FakeDatabase("partial")
        val openFailure = IllegalStateException("database open failed")
        val rollbackFailure = IllegalStateException("migration rollback failed")

        val actualFailure = runCatching {
            openDatabaseWithMigrationRecovery(
                buildDatabase = { database },
                openDatabase = { throw openFailure },
                completeMigration = {},
                closeDatabase = {},
                rollbackMigration = { throw rollbackFailure }
            )
        }.exceptionOrNull()

        assertSame(openFailure, actualFailure)
        assertEquals(1, openFailure.suppressed.size)
        assertSame(rollbackFailure, openFailure.suppressed.single())
    }

    @Test
    fun builderFailureStillRollsBackWithoutClosingAnUncreatedDatabase() {
        val buildFailure = IllegalStateException("Room builder failed")
        val rollbackFailure = IllegalStateException("migration rollback failed")
        var closeAttempted = false
        var openAttempted = false
        var completeAttempted = false

        val actualFailure = runCatching {
            openDatabaseWithMigrationRecovery(
                buildDatabase = { throw buildFailure },
                openDatabase = { openAttempted = true },
                completeMigration = { completeAttempted = true },
                closeDatabase = { closeAttempted = true },
                rollbackMigration = { throw rollbackFailure }
            )
        }.exceptionOrNull()

        assertSame(buildFailure, actualFailure)
        assertFalse("No database exists to close when the builder fails", closeAttempted)
        assertFalse("The database cannot be opened when construction fails", openAttempted)
        assertFalse("Migration cannot be completed when construction fails", completeAttempted)
        assertEquals(1, buildFailure.suppressed.size)
        assertSame(rollbackFailure, buildFailure.suppressed.single())
    }

    @Test
    fun completionFailureClosesDatabaseAndAttemptsRollback() {
        val database = FakeDatabase("opened")
        val completeFailure = IllegalStateException("migration completion failed")
        val closeFailure = IllegalArgumentException("database close failed")
        val rollbackFailure = UnsupportedOperationException("migration rollback failed")
        val operations = mutableListOf<String>()

        val actualFailure = runCatching {
            openDatabaseWithMigrationRecovery(
                buildDatabase = {
                    operations += "build"
                    database
                },
                openDatabase = {
                    operations += "open"
                },
                completeMigration = {
                    operations += "complete"
                    throw completeFailure
                },
                closeDatabase = {
                    operations += "close"
                    throw closeFailure
                },
                rollbackMigration = {
                    operations += "rollback"
                    throw rollbackFailure
                }
            )
        }.exceptionOrNull()

        assertSame(completeFailure, actualFailure)
        assertEquals(listOf("build", "open", "complete", "close", "rollback"), operations)
        assertEquals(2, completeFailure.suppressed.size)
        assertSame(closeFailure, completeFailure.suppressed[0])
        assertSame(rollbackFailure, completeFailure.suppressed[1])
    }

    @Test
    fun successfulOpenCompletesMigrationWithoutRunningRecovery() {
        val database = FakeDatabase("ready")
        val operations = mutableListOf<String>()

        val result = openDatabaseWithMigrationRecovery(
            buildDatabase = {
                operations += "build"
                database
            },
            openDatabase = {
                operations += "open"
            },
            completeMigration = {
                operations += "complete"
            },
            closeDatabase = {
                operations += "close"
            },
            rollbackMigration = {
                operations += "rollback"
            }
        )

        assertSame(database, result)
        assertEquals(listOf("build", "open", "complete"), operations)
    }
}
