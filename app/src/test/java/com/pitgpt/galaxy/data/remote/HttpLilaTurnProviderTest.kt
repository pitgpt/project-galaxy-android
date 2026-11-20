package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
class HttpLilaTurnProviderTest {
    @Test
    fun postsAuthenticatedTurnAndReturnsProviderIdentity() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/lila/turns"),
            200,
            JSONObject()
                .put("conversation_id", "conversation-1")
                .put("text", "Привет! Я Лила.")
                .put("provider_id", "cloud-primary")
                .put("model_id", "model-a")
                .toString()
        )
        val provider = HttpLilaTurnProvider(
            baseUrl = "https://api.example.test",
            accessTokenProvider = GalaxyAccessTokenProvider { "test-access-token" },
            connectionFactory = { connection }
        )

        val result = provider.sendTurn("conversation-1", "Привет")

        assertEquals("conversation-1", result.conversationId)
        assertEquals("Привет! Я Лила.", result.text)
        assertEquals("cloud-primary", result.providerId)
        assertEquals("model-a", result.modelId)
        assertEquals("cloud-primary · model-a", result.displayModel)
        assertEquals("Bearer test-access-token", connection.requestHeaders["Authorization"])
        assertEquals("application/json; charset=utf-8", connection.requestHeaders["Content-Type"])
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)

        val request = JSONObject(String(connection.requestBody.toByteArray(), Charsets.UTF_8))
        assertEquals("conversation-1", request.getString("conversation_id"))
        assertEquals("Привет", request.getString("message"))
        assertFalse(request.has("principal_id"))
    }

    @Test
    fun refusesToSendWhenNoAuthenticatedTokenExists() = runBlocking {
        var opened = false
        val provider = HttpLilaTurnProvider(
            baseUrl = "https://api.example.test",
            accessTokenProvider = null,
            connectionFactory = {
                opened = true
                FakeConnection(it, 200, "{}")
            }
        )

        val error = expectApiError { provider.sendTurn("conversation-1", "hello") }

        assertEquals(401, error.httpStatus)
        assertFalse(opened)
    }

    @Test
    fun mapsProviderUnavailableWithoutExposingResponseBody() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/lila/turns"),
            503,
            "provider-secret-and-internal-diagnostics"
        )
        val provider = HttpLilaTurnProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "test-access-token" },
            connectionFactory = { connection }
        )

        val error = expectApiError { provider.sendTurn("conversation-1", "hello") }

        assertEquals(503, error.httpStatus)
        assertFalse(error.message.orEmpty().contains("provider-secret"))
        assertFalse(error.message.orEmpty().contains("test-access-token"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun rejectsMismatchedConversationAndOversizedResponses() = runBlocking {
        val mismatch = FakeConnection(
            URL("https://api.example.test/v1/lila/turns"),
            200,
            JSONObject().put("conversation_id", "other").put("text", "answer").toString()
        )
        val mismatchProvider = HttpLilaTurnProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "test-access-token" },
            connectionFactory = { mismatch }
        )
        val mismatchError = expectApiError {
            mismatchProvider.sendTurn("conversation-1", "hello")
        }
        assertTrue(mismatchError.message.orEmpty().contains("другого диалога"))
        assertTrue(mismatch.disconnected)

        val oversized = FakeConnection(
            URL("https://api.example.test/v1/lila/turns"),
            200,
            "x".repeat(128 * 1024 + 1)
        )
        val oversizedProvider = HttpLilaTurnProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "test-access-token" },
            connectionFactory = { oversized }
        )
        val oversizedError = expectApiError {
            oversizedProvider.sendTurn("conversation-1", "hello")
        }
        assertTrue(oversizedError.message.orEmpty().contains("допустимый размер"))
        assertTrue(oversized.disconnected)
    }

    @Test
    fun rejectsRemoteCleartextAndMarksPlaceholderEndpointUnconfigured() = runBlocking {
        val insecure = HttpLilaTurnProvider(
            "http://api.example.test",
            GalaxyAccessTokenProvider { "token" }
        )
        assertFalse(insecure.isConfigured)
        val insecureError = expectApiError { insecure.sendTurn("conversation-1", "hello") }
        assertFalse(insecureError.message.orEmpty().contains("token"))

        val placeholder = HttpLilaTurnProvider(
            "https://api.galaxy.invalid",
            GalaxyAccessTokenProvider { "token" }
        )
        assertFalse(placeholder.isConfigured)

        val malformed = HttpLilaTurnProvider(
            "not-a-url",
            GalaxyAccessTokenProvider { "token" }
        )
        assertFalse(malformed.isConfigured)
        val malformedError = expectApiError { malformed.sendTurn("conversation-1", "hello") }
        assertTrue(malformedError.message.orEmpty().contains("invalid"))
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
        private val response: String
    ) : HttpURLConnection(url) {
        val requestBody = ByteArrayOutputStream()
        val requestHeaders = mutableMapOf<String, String>()
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): OutputStream = requestBody

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))

        override fun getResponseCode(): Int = status

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }
    }
}
