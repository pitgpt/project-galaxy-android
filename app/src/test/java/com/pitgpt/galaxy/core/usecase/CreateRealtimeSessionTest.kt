package com.pitgpt.galaxy.core.usecase

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential
import com.pitgpt.galaxy.core.repository.RealtimeSessionProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CreateRealtimeSessionTest {
    @Test
    fun requestsCredentialForSelectedCharacterAndVoice() = runBlocking {
        val provider = FakeProvider(RealtimeSessionCredential("ephemeral-secret", 5_000L, "session-1"))
        val useCase = CreateRealtimeSession(provider, now = { 1_000L })

        val result = useCase("galaxy.default", "voice.warm")

        assertEquals("galaxy.default", provider.characterId)
        assertEquals("voice.warm", provider.voiceId)
        assertEquals("ephemeral-secret", result.clientSecret)
        assertEquals(5_000L, result.expiresAtEpochMillis)
    }

    @Test
    fun rejectsBlankCharacterBeforeCallingProvider() {
        val provider = FakeProvider(RealtimeSessionCredential("secret", 5_000L))
        val useCase = CreateRealtimeSession(provider, now = { 1_000L })

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { useCase("  ", null) }
        }
        assertEquals(null, provider.characterId)
    }

    @Test
    fun rejectsAlreadyExpiredCredential() {
        val provider = FakeProvider(RealtimeSessionCredential("secret", 1_000L))
        val useCase = CreateRealtimeSession(provider, now = { 1_000L })

        assertThrows(IllegalStateException::class.java) {
            runBlocking { useCase("galaxy.default", null) }
        }
    }

    @Test
    fun credentialToStringRedactsSecret() {
        val credential = RealtimeSessionCredential("do-not-log-this", 5_000L)

        assertFalse(credential.toString().contains("do-not-log-this"))
        assert(credential.toString().contains("[REDACTED]"))
    }

    private class FakeProvider(
        private val credential: RealtimeSessionCredential
    ) : RealtimeSessionProvider {
        var characterId: String? = null
        var voiceId: String? = null

        override suspend fun createSession(
            characterId: String,
            voiceId: String?
        ): RealtimeSessionCredential {
            this.characterId = characterId
            this.voiceId = voiceId
            return credential
        }
    }
}
