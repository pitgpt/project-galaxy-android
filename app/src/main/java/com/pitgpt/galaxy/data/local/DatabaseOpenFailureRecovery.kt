package com.pitgpt.galaxy.data.local

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
