package com.pitgpt.galaxy.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseOpenFailureRecoveryTest {
    @Test
    fun keepsOpenFailurePrimaryAndRecordsCloseThenRollbackFailures() {
        val openFailure = IllegalStateException("database open failed")
        val closeFailure = IllegalArgumentException("database close failed")
        val rollbackFailure = UnsupportedOperationException("migration rollback failed")
        var rollbackAttempted = false

        preserveDatabaseOpenFailure(
            primaryFailure = openFailure,
            closeDatabase = { throw closeFailure },
            rollback = {
                rollbackAttempted = true
                throw rollbackFailure
            }
        )

        assertTrue("Rollback must still run when database close fails", rollbackAttempted)
        assertEquals("Both recovery failures must be retained", 2, openFailure.suppressed.size)
        assertSame("Close failure must be recorded first", closeFailure, openFailure.suppressed[0])
        assertSame("Rollback failure must be recorded second", rollbackFailure, openFailure.suppressed[1])
    }

    @Test
    fun avoidsSelfSuppressionAndStillRunsRollback() {
        val openFailure = IllegalStateException("database open failed")
        val rollbackFailure = IllegalStateException("migration rollback failed")
        var rollbackAttempted = false

        preserveDatabaseOpenFailure(
            primaryFailure = openFailure,
            closeDatabase = { throw openFailure },
            rollback = {
                rollbackAttempted = true
                throw rollbackFailure
            }
        )

        assertTrue("Rollback must run after a close callback rethrows the primary failure", rollbackAttempted)
        assertEquals("The primary failure must not be suppressed onto itself", 1, openFailure.suppressed.size)
        assertSame(rollbackFailure, openFailure.suppressed.single())
    }

    @Test
    fun recordsRollbackFailureWhenDatabaseCloseSucceeds() {
        val openFailure = IllegalStateException("database open failed")
        val rollbackFailure = IllegalStateException("migration rollback failed")

        preserveDatabaseOpenFailure(
            primaryFailure = openFailure,
            closeDatabase = {},
            rollback = { throw rollbackFailure }
        )

        assertEquals(1, openFailure.suppressed.size)
        assertSame(rollbackFailure, openFailure.suppressed.single())
    }
}
