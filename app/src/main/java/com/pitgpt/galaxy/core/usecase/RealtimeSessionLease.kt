package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential
import com.pitgpt.galaxy.core.repository.RealtimeSessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the backend lease for one active Realtime connection.
 *
 * The caller starts this only after the transport is connected and closes it when
 * disconnecting. It deliberately does not implement or imply a WebRTC transport.
 */
class RealtimeSessionLease(
    private val credential: RealtimeSessionCredential,
    private val provider: RealtimeSessionProvider,
    private val scope: CoroutineScope,
    private val heartbeatIntervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    private val onLeaseLost: (Exception) -> Unit = {}
) {
    private val mutex = Mutex()
    private var heartbeatJob: Job? = null
    private var closed = false

    init {
        require(!credential.sessionId.isNullOrBlank()) {
            "A Galaxy session ID is required to manage its lease"
        }
        require(heartbeatIntervalMillis > 0L) {
            "Heartbeat interval must be positive"
        }
    }

    /** Starts periodic heartbeats. Calling start more than once is harmless. */
    suspend fun start() = mutex.withLock {
        check(!closed) { "Session lease is already closed" }
        if (heartbeatJob?.isActive == true) return@withLock

        val sessionId = requireNotNull(credential.sessionId)
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatIntervalMillis)
                try {
                    provider.heartbeat(sessionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    onLeaseLost(failure)
                    break
                }
            }
        }
    }

    /**
     * Stops heartbeats and releases the backend lease once.
     * The backend DELETE is idempotent, but this class avoids duplicate calls too.
     */
    suspend fun close() {
        val job = mutex.withLock {
            if (closed) return
            closed = true
            heartbeatJob.also { heartbeatJob = null }
        }
        job?.cancel()
        job?.join()
        provider.closeSession(requireNotNull(credential.sessionId))
    }

    private companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 30_000L
    }
}
