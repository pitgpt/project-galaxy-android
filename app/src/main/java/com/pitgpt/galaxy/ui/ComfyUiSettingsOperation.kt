package com.pitgpt.galaxy.ui

import kotlinx.coroutines.CancellationException

/**
 * Captures ordinary settings-operation failures for user-facing UI messages while preserving
 * structured coroutine cancellation from a disposed screen or cancelled operation.
 */
internal suspend fun <T> captureComfyUiSettingsResult(
    operation: suspend () -> T
): Result<T> = try {
    Result.success(operation())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
