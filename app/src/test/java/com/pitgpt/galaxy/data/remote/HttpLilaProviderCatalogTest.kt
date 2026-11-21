package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
class HttpLilaProviderCatalogTest {
    @Test
    fun createsProviderWithSecretOnlyInAuthenticatedRequestBody() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/lila/providers"),
            200,
            providerJson()
        )
        val catalog = HttpLilaProviderCatalog(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "galaxy-access-token" },
            connectionFactory = { connection }
        )

        val provider = catalog.createProvider(
            providerId = "cloud-main",
            providerType = "cloud",
            baseUrl = "https://models.example.test/v1",
            modelId = "model-a",
            apiKey = "provider-secret-value",
            timeoutSeconds = 30.0
        )

        val request = JSONObject(String(connection.requestBody.toByteArray(), Charsets.UTF_8))
        assertEquals("provider-secret-value", request.getString("api_key"))
        assertEquals("Bearer galaxy-access-token", connection.requestHeaders["Authorization"])
        assertFalse(connection.requestHeaders.values.any { it.contains("provider-secret-value") })
        assertEquals("cloud-main", provider.providerId)
        assertTrue(provider.hasApiKey)
        assertFalse(provider.toString().contains("provider-secret-value"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test
    fun listsSafeProviderSummariesWithoutEndpointOrSecret() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/lila/providers"),
            200,
            JSONObject().put("providers", JSONArray().put(JSONObject(providerJson()))).toString()
        )
        val catalog = HttpLilaProviderCatalog(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = { connection }
        )

        val providers = catalog.listProviders()

        assertEquals(1, providers.size)
        assertEquals("cloud-main", providers.single().providerId)
        assertEquals("model-a", providers.single().modelId)
        assertTrue(providers.single().hasApiKey)
        assertFalse(providers.single().toString().contains("api_key"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun probesModelDiscoveryAndStoresConversationSelection() = runBlocking {
        val probeConnection = FakeConnection(
            URL("https://api.example.test/v1/lila/providers/cloud-main/probe"),
            200,
            JSONObject()
                .put("provider_id", "cloud-main")
                .put("configured_model_id", "model-a")
                .put("connection_reachable", true)
                .put("model_discovery_supported", true)
                .put("configured_model_available", true)
                .put("model_ids", JSONArray().put("model-a").put("model-b"))
                .put("chat_completion_verified", false)
                .toString()
        )
        val selectionConnection = FakeConnection(
            URL("https://api.example.test/v1/lila/conversations/conversation-1/model"),
            200,
            JSONObject()
                .put("conversation_id", "conversation-1")
                .put("selected", true)
                .put("provider_id", "cloud-main")
                .put("model_id", "model-b")
                .toString()
        )
        val connections = mutableListOf<FakeConnection>()
        val catalog = HttpLilaProviderCatalog(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = {
                val connection = if (connections.isEmpty()) probeConnection else selectionConnection
                connections += connection
                connection
            }
        )

        val probe = catalog.probeProvider("cloud-main")
        val selection = catalog.setConversationModel("conversation-1", "cloud-main", "model-b")

        assertTrue(probe.connectionReachable)
        assertTrue(probe.modelDiscoverySupported)
        assertEquals(listOf("model-a", "model-b"), probe.modelIds)
        assertFalse(probe.chatCompletionVerified)
        assertTrue(selection.selected)
        assertEquals("model-b", selection.modelId)
        assertEquals("PUT", selectionConnection.requestMethod)
        val request = JSONObject(String(selectionConnection.requestBody.toByteArray(), Charsets.UTF_8))
        assertEquals("cloud-main", request.getString("provider_id"))
        assertEquals("model-b", request.getString("model_id"))
        assertTrue(connections.all { it.disconnected })
    }

    @Test
    fun restoresExistingConversationModelSelection() = runBlocking {
        val connection = FakeConnection(
            URL("https://api.example.test/v1/lila/conversations/conversation-1/model"),
            200,
            JSONObject()
                .put("conversation_id", "conversation-1")
                .put("selected", true)
                .put("provider_id", "local-main")
                .put("model_id", "qwen-local")
                .toString()
        )
        val catalog = HttpLilaProviderCatalog(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = { connection }
        )

        val selection = catalog.getConversationModel("conversation-1")

        assertTrue(selection.selected)
        assertEquals("local-main", selection.providerId)
        assertEquals("qwen-local", selection.modelId)
        assertEquals("GET", connection.requestMethod)
        assertTrue(connection.disconnected)
    }

    @Test
    fun refusesCatalogCallsWithoutSessionAndRejectsRemoteCleartext() = runBlocking {
        var opened = false
        val noSession = HttpLilaProviderCatalog(
            "https://api.example.test",
            null,
            connectionFactory = {
                opened = true
                FakeConnection(it, 200, "{}")
            }
        )
        val authError = expectApiError { noSession.listProviders() }
        assertEquals(401, authError.httpStatus)
        assertFalse(opened)

        val insecure = HttpLilaProviderCatalog(
            "http://api.example.test",
            GalaxyAccessTokenProvider { "token" }
        )
        assertFalse(insecure.isConfigured)
        val insecureError = expectApiError { insecure.listProviders() }
        assertTrue(insecureError.message.orEmpty().contains("HTTPS"))
    }

    @Test
    fun sanitizesProviderErrorsAndBoundsResponses() = runBlocking {
        val unauthorized = FakeConnection(
            URL("https://api.example.test/v1/lila/providers"),
            401,
            "secret endpoint diagnostic"
        )
        val catalog = HttpLilaProviderCatalog(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = { unauthorized }
        )
        val error = expectApiError { catalog.listProviders() }
        assertEquals(401, error.httpStatus)
        assertFalse(error.message.orEmpty().contains("secret endpoint"))

        val oversized = FakeConnection(
            URL("https://api.example.test/v1/lila/providers"),
            200,
            "x".repeat(128 * 1024 + 1)
        )
        val oversizedCatalog = HttpLilaProviderCatalog(
            "https://api.example.test",
            GalaxyAccessTokenProvider { "token" },
            connectionFactory = { oversized }
        )
        val sizeError = expectApiError { oversizedCatalog.listProviders() }
        assertTrue(sizeError.message.orEmpty().contains("размер"))
        assertTrue(unauthorized.disconnected)
        assertTrue(oversized.disconnected)
    }

    private suspend fun expectApiError(block: suspend () -> Unit): GalaxyApiException {
        try {
            block()
        } catch (error: GalaxyApiException) {
            return error
        }
        throw AssertionError("Expected GalaxyApiException")
    }

    private fun providerJson(): String = JSONObject()
        .put("provider_id", "cloud-main")
        .put("provider_type", "cloud")
        .put("model_id", "model-a")
        .put("capabilities", JSONArray().put("chat"))
        .put("timeout_seconds", 30.0)
        .put("enabled", true)
        .put("has_api_key", true)
        .toString()

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val response: String
    ) : HttpURLConnection(url) {
        val requestBody = ByteArrayOutputStream()
        val requestHeaders = mutableMapOf<String, String>()
        var disconnected = false

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
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
