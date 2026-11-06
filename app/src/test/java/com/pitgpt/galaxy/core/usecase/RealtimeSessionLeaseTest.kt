package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential
import com.pitgpt.galaxy.core.repository.RealtimeSessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSessionLeaseTest {
    @Test
    fun heartbeatsPeriodicallyAndCloseStopsFurtherRenewals() = runBlocking {
        val provider = FakeProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lease = RealtimeSessionLease(
                credential(), provider, scope, heartbeatIntervalMillis = 15L
            )
            lease.start()
            lease.start()
            delay(55L)
            lease.close()
            val countAtClose = provider.heartbeats
            delay(40L)

            assertTrue(countAtClose >= 2)
            assertEquals(countAtClose, provider.heartbeats)
            assertEquals(listOf("session-1"), provider.closedSessions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closeBeforeFirstHeartbeatReleasesLeaseOnce() = runBlocking {
        val provider = FakeProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lease = RealtimeSessionLease(
                credential(), provider, scope, heartbeatIntervalMillis = 10_000L
            )
            lease.start()
            lease.close()
            lease.close()

            assertEquals(0, provider.heartbeats)
            assertEquals(listOf("session-1"), provider.closedSessions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun heartbeatFailureNotifiesOwnerAndStopsRenewal() = runBlocking {
        val provider = FakeProvider(failHeartbeat = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var reported: Exception? = null
        try {
            val lease = RealtimeSessionLease(
                credential(), provider, scope, heartbeatIntervalMillis = 10L,
                onLeaseLost = { reported = it }
            )
            lease.start()
            delay(50L)

            assertTrue(reported is IllegalStateException)
            assertEquals(1, provider.heartbeats)
            lease.close()
            assertEquals(listOf("session-1"), provider.closedSessions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requiresSessionId() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RealtimeSessionLease(
                RealtimeSessionCredential("secret", 100_000L),
                FakeProvider(),
                CoroutineScope(SupervisorJob() + Dispatchers.Default)
            )
        }
        assertFalse(error.message.isNullOrBlank())
    }

    private fun credential() =
        RealtimeSessionCredential("ephemeral-secret", 100_000L, "session-1")

    private class FakeProvider(
        private val failHeartbeat: Boolean = false
    ) : RealtimeSessionProvider {
        var heartbeats = 0
        val closedSessions = mutableListOf<String>()

        override suspend fun createSession(
            characterId: String,
            voiceId: String?
        ) = credential()

        override suspend fun heartbeat(sessionId: String) {
            heartbeats += 1
            if (failHeartbeat) throw IllegalStateException("lease expired")
        }

        override suspend fun closeSession(sessionId: String) {
            closedSessions += sessionId
        }
    }
}
