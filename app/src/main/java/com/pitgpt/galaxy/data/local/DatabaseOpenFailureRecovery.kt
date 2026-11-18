package com.pitgpt.galaxy.data.local

/**
 * Builds, opens, and finalizes an encrypted database migration as one recoverable operation.
 *
 * The migration rollback is attempted even when database construction fails before a Room
 * instance exists. If a partially constructed database is available, it is closed before rollback.
 * The original construction/open/finalization exception remains primary.
 */
internal fun <Database : Any> openDatabaseWithMigrationRecovery(
    buildDatabase: () -> Database,
    openDatabase: (Database) -> Unit,
    completeMigration: () -> Unit,
    closeDatabase: (Database) -> Unit,
    rollbackMigration: () -> Unit
): Database {
    var database: Database? = null
    try {
        val builtDatabase = buildDatabase()
        database = builtDatabase
        openDatabase(builtDatabase)
        completeMigration()
        return builtDatabase
    } catch (failure: Exception) {
        preserveDatabaseOpenFailure(
            primaryFailure = failure,
            closeDatabase = {
                val partiallyOpenedDatabase = database
                if (partiallyOpenedDatabase != null) {
                    closeDatabase(partiallyOpenedDatabase)
                }
            },
            rollback = rollbackMigration
        )
        throw failure
    }
}

/**
 * Runs both recovery steps after an encrypted database open failure.
 *
 * The original open failure remains primary. Failures from closing the partially opened database
 * and rolling back the pending migration are attached in deterministic order. A cleanup callback
 * that rethrows the original failure instance must not trigger self-suppression or prevent the
 * remaining recovery step from running.
 */
internal fun preserveDatabaseOpenFailure(
    primaryFailure: Throwable,
    closeDatabase: () -> Unit,
    rollback: () -> Unit
) {
    fun recordFailure(action: () -> Unit) {
        val recoveryFailure = runCatching(action).exceptionOrNull() ?: return
        if (recoveryFailure !== primaryFailure) {
            primaryFailure.addSuppressed(recoveryFailure)
        }
    }

    recordFailure(closeDatabase)
    recordFailure(rollback)
}
