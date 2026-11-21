package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class HttpLilaTurnProviderTest {
    private val idempotencyKey = "00000000-0000-4000-8000-000000000201"
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

        val result = provider.sendTurn("conversation-1", "Привет", idempotencyKey)

        assertEquals("conversation-1", result.conversationId)
        assertEquals("Привет! Я Лила.", result.text)
        assertEquals("cloud-primary", result.providerId)
        assertEquals("model-a", result.modelId)
        assertEquals("cloud-primary · model-a", result.displayModel)
        assertEquals("Bearer test-access-token", connection.requestHeaders["Authorization"])
        assertEquals(idempotencyKey, connection.requestHeaders["Idempotency-Key"])
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

        val error = expectApiError { provider.sendTurn("conversation-1", "hello", idempotencyKey) }

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

        val error = expectApiError { provider.sendTurn("conversation-1", "hello", idempotencyKey) }

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
            mismatchProvider.sendTurn("conversation-1", "hello", idempotencyKey)
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
            oversizedProvider.sendTurn("conversation-1", "hello", idempotencyKey)
        }
        assertTrue(oversizedError.message.orEmpty().contains("допустимый размер"))
        assertTrue(oversized.disconnected)
    }

    @Test
    fun conversationIdentityIsStableAcrossClientsAndSeparatedByOwnerAndCharacter() {
        val first = stableLilaConversationId("owner-partition-a", "galaxy.default")

        assertEquals(first, stableLilaConversationId("owner-partition-a", "galaxy.default"))
        assertNotEquals(first, stableLilaConversationId("owner-partition-b", "galaxy.default"))
        assertNotEquals(first, stableLilaConversationId("owner-partition-a", "another-character"))
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test
    fun loadsAuthenticatedHistoryPagesInChronologicalOrder() = runBlocking {
        val conversationId = "00000000-0000-4000-8000-000000000301"
        val responses = listOf(
            JSONObject()
                .put("conversation_id", conversationId)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("sequence", 3).put("role", "user")
                            .put("content", "third").put("created_at_epoch_millis", 3000))
                        .put(JSONObject().put("sequence", 4).put("role", "assistant")
                            .put("content", "fourth").put("created_at_epoch_millis", 4000)
                            .put("provider_id", "cloud-primary").put("model_id", "model-a"))
                )
                .put("next_before_sequence", 3)
                .toString(),
            JSONObject()
                .put("conversation_id", conversationId)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("sequence", 1).put("role", "user")
                            .put("content", "first").put("created_at_epoch_millis", 1000))
                        .put(JSONObject().put("sequence", 2).put("role", "assistant")
                            .put("content", "second").put("created_at_epoch_millis", 2000))
                )
                .put("next_before_sequence", JSONObject.NULL)
                .toString()
        )
        val connections = mutableListOf<FakeConnection>()
        val provider = HttpLilaTurnProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "test-access-token" },
            connectionFactory = { url ->
                FakeConnection(url, 200, responses[connections.size]).also(connections::add)
            }
        )

        val history = provider.loadAllHistory(conversationId)

        assertEquals(listOf("first", "second", "third", "fourth"), history.map { it.content })
        assertEquals(listOf(1L, 2L, 3L, 4L), history.map { it.sequence })
        assertEquals("cloud-primary", history.last().providerId)
        assertEquals("model-a", history.last().modelId)
        assertEquals("cloud-primary · model-a", history.last().displayModelLabel)
        assertEquals(2, connections.size)
        assertEquals("GET", connections[0].requestMethod)
        assertEquals("Bearer test-access-token", connections[0].requestHeaders["Authorization"])
        assertTrue(connections[0].url.query.orEmpty().contains("limit=20"))
        assertTrue(connections[1].url.query.orEmpty().contains("before_sequence=3"))
        assertTrue(connections.all { it.disconnected })
    }

    @Test
    fun historyRequiresAnAuthenticatedTokenBeforeOpeningConnection() = runBlocking {
        var opened = false
        val provider = HttpLilaTurnProvider(
            "https://api.example.test",
            accessTokenProvider = null,
            connectionFactory = {
                opened = true
                FakeConnection(it, 200, "{}")
            }
        )

        val error = expectApiError { provider.loadAllHistory("00000000-0000-4000-8000-000000000302") }

        assertEquals(401, error.httpStatus)
        assertFalse(opened)
    }

    @Test
    fun rejectsRemoteCleartextAndMarksPlaceholderEndpointUnconfigured() = runBlocking {
        val insecure = HttpLilaTurnProvider(
            "http://api.example.test",
            GalaxyAccessTokenProvider { "token" }
        )
        assertFalse(insecure.isConfigured)
        val insecureError = expectApiError { insecure.sendTurn("conversation-1", "hello", idempotencyKey) }
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
        val malformedError = expectApiError { malformed.sendTurn("conversation-1", "hello", idempotencyKey) }
        assertTrue(malformedError.message.orEmpty().contains("host"))
    }

    @Test
    fun explainsWhenProviderConnectionFailedBeforeRequestWasSent() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/lila/turns"),
            503,
            """{"detail":"provider-secret private-provider-detail"}""",
            mapOf("X-Lila-Retry-Safe" to "true")
        )
        val provider = HttpLilaTurnProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "supabase-access-token" },
            connectionFactory = { connection }
        )

        val error = expectApiError {
            provider.sendTurn("conversation-1", "Привет", idempotencyKey)
        }

        assertEquals(503, error.httpStatus)
        assertTrue(error.message.orEmpty().contains("повтор безопасен", ignoreCase = true))
        assertFalse(error.message.orEmpty().contains("provider-secret"))
        assertFalse(error.message.orEmpty().contains("private-provider-detail"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun warnsWhenProviderMayHaveReceivedTurnWithoutConfirmedResponse() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/lila/turns"),
            503,
            """{"detail":"provider-secret private diagnostic"}""",
            mapOf("X-Lila-Outcome-Unknown" to "true")
        )
        val provider = HttpLilaTurnProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "supabase-access-token" },
            connectionFactory = { connection }
        )

        val error = expectApiError {
            provider.sendTurn("conversation-1", "Привет", idempotencyKey)
        }

        assertEquals(503, error.httpStatus)
        assertTrue(error.message.orEmpty().contains("могла получить", ignoreCase = true))
        assertTrue(error.message.orEmpty().contains("не отправляй", ignoreCase = true))
        assertFalse(error.message.orEmpty().contains("provider-secret"))
        assertFalse(error.message.orEmpty().contains("private diagnostic"))
        assertFalse(error.message.orEmpty().contains("supabase-access-token"))
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
        private val response: String,
        private val responseHeaders: Map<String, String> = emptyMap()
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

        override fun getHeaderField(name: String): String? = responseHeaders[name]

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }
    }
}


    @Test
    fun recoversCommittedTurnFromStatusAfterTheOriginalResponseIsLost() = runBlocking {
        val turn = FakeConnection(
            URL("https://api.example.test/v1/lila/turns"),
            503,
            """{"detail":"provider outcome unknown"}""",
            mapOf("X-Lila-Outcome-Unknown" to "true")
        )
        val status = FakeConnection(
            URL("https://api.example.test/v1/lila/conversations/conversation-1/turns/$idempotencyKey"),
            200,
            JSONObject()
                .put("conversation_id", "conversation-1")
                .put("status", "completed")
                .put(
                    "response",
                    JSONObject()
                        .put("conversation_id", "conversation-1")
                        .put("text", "Ответ уже сохранён")
                        .put("provider_id", "cloud-primary")
                        .put("model_id", "model-a")
                )
                .toString()
        )
        val provider = HttpLilaTurnProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "supabase-access-token" },
            connectionFactory = { url ->
                if (url.path.endsWith("/$idempotencyKey")) status else turn
            }
        )

        val result = provider.sendTurn("conversation-1", "Привет", idempotencyKey)

        assertEquals("Ответ уже сохранён", result.text)
        assertEquals("cloud-primary", result.providerId)
        assertEquals("model-a", result.modelId)
        assertEquals("POST", turn.requestMethod)
        assertEquals("GET", status.requestMethod)
        assertEquals("Bearer supabase-access-token", status.requestHeaders["Authorization"])
        assertTrue(turn.disconnected)
        assertTrue(status.disconnected)
    }

    @Test
    fun keepsAmbiguousWarningWhenTurnStatusIsStillPending() = runBlocking {
        val turn = FakeConnection(
            URL("https://api.example.test/v1/lila/turns"),
            503,
            """{"detail":"provider outcome unknown"}""",
            mapOf("X-Lila-Outcome-Unknown" to "true")
        )
        val status = FakeConnection(
            URL("https://api.example.test/v1/lila/conversations/conversation-1/turns/$idempotencyKey"),
            200,
            JSONObject()
                .put("conversation_id", "conversation-1")
                .put("status", "pending")
                .put("response", JSONObject.NULL)
                .toString()
        )
        val provider = HttpLilaTurnProvider(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "supabase-access-token" },
            connectionFactory = { url ->
                if (url.path.endsWith("/$idempotencyKey")) status else turn
            }
        )

        val error = expectApiError {
            provider.sendTurn("conversation-1", "Привет", idempotencyKey)
        }

        assertEquals(503, error.httpStatus)
        assertTrue(error.message.orEmpty().contains("могла получить", ignoreCase = true))
        assertTrue(error.message.orEmpty().contains("не отправляй", ignoreCase = true))
        assertEquals("POST", turn.requestMethod)
        assertEquals("GET", status.requestMethod)
        assertTrue(turn.disconnected)
        assertTrue(status.disconnected)
    }
