package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection

/**
 * Runs one blocking HttpURLConnection operation on Dispatchers.IO.
 *
 * Coroutine cancellation alone does not reliably interrupt HttpURLConnection reads or writes.
 * Disconnect the socket from the cancellation handler so the blocking operation can exit promptly.
 */
internal suspend fun <T> HttpURLConnection.executeCancellable(block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { disconnect() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            val result = try {
                Result.success(block())
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            continuation.resumeWith(result)
        })
    }
