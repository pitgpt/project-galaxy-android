package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection

/**
 * Runs one blocking HttpURLConnection operation on Dispatchers.IO.
 *
 * Coroutine cancellation alone does not reliably interrupt HttpURLConnection reads or writes.
 * Disconnect the socket before cancelling the worker so the blocking operation can exit promptly.
 */
internal suspend fun <T> HttpURLConnection.executeCancellable(block: () -> T): T =
    coroutineScope {
        val operation = async(Dispatchers.IO) { block() }
        try {
            operation.await()
        } catch (cancelled: CancellationException) {
            disconnect()
            operation.cancel()
            throw cancelled
        }
    }
