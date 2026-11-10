package com.pitgpt.galaxy.data.providers

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenAiCompatibleChatClientTest {
    private val provider = AiProviderConfig("test", "Test provider", "https://api.example.test/v1", "test-key", "test-model")

    @Test fun sendsModelAndTurnsToCompatibleEndpoint() = runBlocking {
        var capturedUrl = ""; var capturedKey = ""; var capturedBody = ""
        val transport = ProviderHttpTransport { url, key, body ->
            capturedUrl = url; capturedKey = key; capturedBody = body
            ProviderHttpResponse(200, """{"model":"test-model","choices":[{"message":{"role":"assistant","content":"Hello from test"}}]}""")
        }
        val result = OpenAiCompatibleChatClient(transport).complete(provider, listOf(ChatTurn("user", "Hello")), temperature = null)
        assertEquals("https://api.example.test/v1/chat/completions", capturedUrl)
        assertEquals("test-key", capturedKey)
        assertEquals("test-model", JSONObject(capturedBody).getString("model"))
        assertEquals("Hello from test", result.text)
    }

    @Test fun sendsOptionalTemperatureInRequestBody() = runBlocking {
        var capturedBody = ""
        val transport = ProviderHttpTransport { _, _, body ->
            capturedBody = body
            ProviderHttpResponse(200, """{"choices":[{"message":{"content":"Hello"}}]}""")
        }

        OpenAiCompatibleChatClient(transport).complete(
            provider,
            listOf(ChatTurn("user", "Hello")),
            temperature = 0.4
        )

        assertEquals(0.4, JSONObject(capturedBody).getDouble("temperature"), 0.0)
    }

    @Test fun rejectsInsecureUrlBeforeSendingRequest() {
        var called = false
        val transport = ProviderHttpTransport { _, _, _ -> called = true; ProviderHttpResponse(200, "{}") }
        val insecure = provider.copy(baseUrl = "http://api.example.test/v1")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { OpenAiCompatibleChatClient(transport).complete(insecure, listOf(ChatTurn("user", "Hi")), temperature = null) }
        }
        assertFalse(called)
    }

    @Test fun rejectsProviderUrlContainingCredentialsQueryOrFragment() {
        val unsafeUrls = listOf(
            "https://user:password@api.example.test/v1",
            "https://api.example.test/v1?token=secret",
            "https://api.example.test/v1#fragment"
        )
        var calls = 0
        val transport = ProviderHttpTransport { _, _, _ ->
            calls++
            ProviderHttpResponse(200, """{"choices":[{"message":{"content":"unexpected"}}]}""")
        }

        unsafeUrls.forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    OpenAiCompatibleChatClient(transport).complete(
                        provider.copy(baseUrl = url),
                        listOf(ChatTurn("user", "Hi")),
                        temperature = null
                    )
                }
            }
        }

        assertEquals(0, calls)
    }

    @Test fun rejectsBlankCredentialsAndUnsupportedTurnsBeforeSendingRequest() {
        var calls = 0
        val transport = ProviderHttpTransport { _, _, _ ->
            calls++
            ProviderHttpResponse(200, """{"choices":[{"message":{"content":"unexpected"}}]}""")
        }
        val client = OpenAiCompatibleChatClient(transport)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.complete(provider.copy(apiKey = " "), listOf(ChatTurn("user", "Hi")), null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.complete(provider, listOf(ChatTurn("tool", "Hi")), null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.complete(provider, listOf(ChatTurn("user", " ")), null) }
        }

        assertEquals(0, calls)
    }

    @Test fun rejectsInvalidJsonResponseWithoutExposingResponseBody() {
        val transport = ProviderHttpTransport { _, _, _ ->
            ProviderHttpResponse(200, "not-json")
        }

        val error = assertThrows(ProviderResponseException::class.java) {
            runBlocking { OpenAiCompatibleChatClient(transport).complete(provider, listOf(ChatTurn("user", "Hi")), null) }
        }

        assertEquals("Provider returned invalid JSON", error.message)
    }

    @Test fun rejectsResponseWithoutAssistantText() {
        val transport = ProviderHttpTransport { _, _, _ ->
            ProviderHttpResponse(200, """{"choices":[{"message":{"content":" "}}]}""")
        }

        val error = assertThrows(ProviderResponseException::class.java) {
            runBlocking { OpenAiCompatibleChatClient(transport).complete(provider, listOf(ChatTurn("user", "Hi")), null) }
        }

        assertEquals("Provider response did not contain assistant text", error.message)
    }

    @Test fun doesNotExposeProviderBodyInHttpErrors() {
        val transport = ProviderHttpTransport { _, _, _ -> ProviderHttpResponse(401, """{"error":"secret diagnostic"}""") }
        val error = assertThrows(ProviderRequestException::class.java) {
            runBlocking { OpenAiCompatibleChatClient(transport).complete(provider, listOf(ChatTurn("user", "Hi")), temperature = null) }
        }
        assertEquals(401, error.statusCode)
        assertEquals("Provider rejected the credentials or access (HTTP 401)", error.message)
    }
}
