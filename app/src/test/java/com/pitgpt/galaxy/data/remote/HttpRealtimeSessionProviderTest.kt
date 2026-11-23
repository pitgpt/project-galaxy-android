package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class HttpRealtimeSessionProviderTest {
    @Test
    fun rejectsInsecureRemoteUrl() {
        try {
            HttpRealtimeSessionProvider("http://api.example.com", GalaxyAccessTokenProvider { "token" })
            throw AssertionError("Expected insecure URL to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.orEmpty().contains("token"))
        }
    }

    @Test
    fun acceptsHttpsAndEmulatorUrls() {
        HttpRealtimeSessionProvider("https://api.example.com/", GalaxyAccessTokenProvider { "token" })
        HttpRealtimeSessionProvider("http://10.0.2.2:8000/", GalaxyAccessTokenProvider { "token" })
        HttpRealtimeSessionProvider("http://127.0.0.1:8000/", GalaxyAccessTokenProvider { "token" })
    }

    @Test
    fun rejectsLookalikeLocalHostAndEmbeddedCredentials() {
        listOf(
            "http://127.0.0.1.attacker.test",
            "http://10.0.2.2.attacker.test",
            "https://user:password@api.example.com"
        ).forEach { value ->
            try {
                HttpRealtimeSessionProvider(value, GalaxyAccessTokenProvider { "token" })
                throw AssertionError("Expected URL to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.orEmpty().contains("password"))
            }
        }
    }

    @Test
    fun apiExceptionContainsOnlySafeMessage() {
        val exception = GalaxyApiException(502, "Galaxy service is temporarily unavailable")
        assertEquals(502, exception.httpStatus)
        assertFalse(exception.message.orEmpty().contains("ephemeral-test-secret"))
        assertFalse(exception.message.orEmpty().contains("account-access-token"))
    }

    @Test
    fun parsesSessionCredentialWithoutExposingItInToString() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/realtime/sessions"),
            200,
            JSONObject()
                .put("client_secret", "ephemeral-session-secret")
                .put("expires_at_epoch_millis", 1_900_000_000_000L)
                .put("session_id", "session-123")
                .toString()
        )
        val provider = HttpRealtimeSessionProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "supabase-access-token" },
            connectionFactory = { connection }
        )

        val credential = provider.createSession("lila", "voice-main")

        assertEquals("ephemeral-session-secret", credential.clientSecret)
        assertEquals(1_900_000_000_000L, credential.expiresAtEpochMillis)
        assertEquals("session-123", credential.sessionId)
        assertFalse(credential.toString().contains("ephemeral-session-secret"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer supabase-access-token", connection.requestHeaders["Authorization"])
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test
    fun cancellationDisconnectsAnInFlightSessionRequest() = runBlocking {
        val connection = BlockingHttpURLConnection(
            URL("https://api.example.test/v1/realtime/sessions")
        )
        val provider = HttpRealtimeSessionProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "supabase-access-token" },
            connectionFactory = { connection }
        )

        val request = launch { provider.createSession("lila", "voice-main") }
        assertTrue(
            withContext(Dispatchers.IO) {
                connection.responseCodeStarted.await(2, TimeUnit.SECONDS)
            }
        )

        withTimeout(2_000) { request.cancelAndJoin() }

        assertTrue(connection.disconnected)
    }

    @Test
    fun rejectsDeclaredOversizedResponseBeforeReadingBody() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/realtime/sessions"),
            200,
            "private-provider-diagnostic",
            mapOf("Content-Length" to (64 * 1024 + 1).toString())
        )
        val provider = HttpRealtimeSessionProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = { connection }
        )

        val error = expectApiError { provider.createSession("lila", "voice-main") }

        assertTrue(error.message.orEmpty().contains("size limit"))
        assertFalse(error.message.orEmpty().contains("private-provider-diagnostic"))
        assertEquals(0, connection.bytesRead)
        assertTrue(connection.disconnected)
    }

    @Test
    fun stopsChunkedResponseReadAtLimitPlusOneByte() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/realtime/sessions"),
            200,
            "x".repeat(64 * 1024 + 1)
        )
        val provider = HttpRealtimeSessionProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = { connection }
        )

        val error = expectApiError { provider.createSession("lila", "voice-main") }

        assertTrue(error.message.orEmpty().contains("size limit"))
        assertEquals(64 * 1024 + 1, connection.bytesRead)
        assertTrue(connection.disconnected)
    }

    @Test
    fun sanitizesHttpErrorWithoutReadingProviderBody() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/realtime/sessions"),
            401,
            "provider-secret-and-internal-diagnostic"
        )
        val provider = HttpRealtimeSessionProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = { connection }
        )

        val error = expectApiError { provider.createSession("lila", "voice-main") }

        assertEquals(401, error.httpStatus)
        assertFalse(error.message.orEmpty().contains("provider-secret"))
        assertEquals(0, connection.bytesRead)
        assertTrue(connection.disconnected)
    }

    @Test
    fun rejectsOversizedClientSecretField() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/realtime/sessions"),
            200,
            JSONObject()
                .put("client_secret", "x".repeat(8 * 1024 + 1))
                .put("expires_at_epoch_millis", 1_900_000_000_000L)
                .put("session_id", "session-123")
                .toString()
        )
        val provider = HttpRealtimeSessionProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = { connection }
        )

        val error = expectApiError { provider.createSession("lila", "voice-main") }

        assertTrue(error.message.orEmpty().contains("invalid session response"))
        assertFalse(error.message.orEmpty().contains("xxxx"))
        assertTrue(connection.disconnected)
    }

    private suspend fun expectApiError(block: suspend () -> Unit): GalaxyApiException {
        try {
            block()
        } catch (error: GalaxyApiException) {
            return error
        }
        throw AssertionError("Expected GalaxyApiException")
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        response: String,
        private val responseHeaders: Map<String, String> = emptyMap()
    ) : HttpURLConnection(url) {
        private val responseBytes = response.toByteArray(Charsets.UTF_8)
        private val requestBody = ByteArrayOutputStream()
        val requestHeaders = mutableMapOf<String, String>()
        var bytesRead = 0
            private set
        var disconnected = false
            private set

        override fun connect() = Unit
        override fun disconnect() {
            disconnected = true
        }
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = requestBody
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(responseBytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) bytesRead += count
                return count
            }
        }
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? = responseHeaders[name]
        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }
    }
}
