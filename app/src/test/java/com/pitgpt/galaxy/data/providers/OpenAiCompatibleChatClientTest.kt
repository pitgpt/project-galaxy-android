package com.pitgpt.galaxy.data.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        val result = OpenAiCompatibleChatClient(transport).complete(provider, listOf(ChatTurn("user", "Hello")))
        assertEquals("https://api.example.test/v1/chat/completions", capturedUrl)
        assertEquals("test-key", capturedKey)
        assertEquals("test-model", org.json.JSONObject(capturedBody).getString("model"))
        assertEquals("Hello from test", result.text)
    }

    @Test fun rejectsInsecureUrlBeforeSendingRequest() {
        var called = false
        val transport = ProviderHttpTransport { _, _, _ -> called = true; ProviderHttpResponse(200, "{}") }
        val insecure = provider.copy(baseUrl = "http://api.example.test/v1")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { OpenAiCompatibleChatClient(transport).complete(insecure, listOf(ChatTurn("user", "Hi"))) }
        }
        assertEquals(false, called)
    }

    @Test fun doesNotExposeProviderBodyInHttpErrors() {
        val transport = ProviderHttpTransport { _, _, _ -> ProviderHttpResponse(401, """{"error":"secret diagnostic"}""") }
        val error = assertThrows(ProviderRequestException::class.java) {
            runBlocking { OpenAiCompatibleChatClient(transport).complete(provider, listOf(ChatTurn("user", "Hi"))) }
        }
        assertEquals(401, error.statusCode)
        assertEquals("Provider rejected the credentials or access (HTTP 401)", error.message)
    }
}
