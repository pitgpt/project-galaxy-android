package com.pitgpt.galaxy.data.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ChatTurn(
    val role: String,
    val content: String
)

data class ChatCompletion(
    val text: String,
    val model: String
)

data class ProviderHttpResponse(
    val statusCode: Int,
    val body: String
)

/** Small seam that lets the HTTP implementation be tested without a real API key. */
fun interface ProviderHttpTransport {
    suspend fun post(url: String, apiKey: String, body: String): ProviderHttpResponse
}

class UrlConnectionProviderTransport : ProviderHttpTransport {
    override suspend fun post(url: String, apiKey: String, body: String): ProviderHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            // HttpURLConnection is blocking. Disconnect it as soon as the caller cancels,
            // so a cancelled chat request does not keep waiting for the network timeout.
            continuation.invokeOnCancellation { connection.disconnect() }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (!continuation.isActive) {
                    connection.disconnect()
                    return@Runnable
                }
                try {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    continuation.resumeWith(Result.success(ProviderHttpResponse(code, responseBody)))
                } catch (error: Exception) {
                    continuation.resumeWith(Result.failure(error))
                } finally {
                    connection.disconnect()
                }
            })
        }
}

/**
 * OpenAI-compatible Chat Completions adapter.
 * API keys are only used for the request and are never included in errors or logs.
 */
class OpenAiCompatibleChatClient(
    private val transport: ProviderHttpTransport = UrlConnectionProviderTransport()
) {
    suspend fun complete(
        provider: AiProviderConfig,
        turns: List<ChatTurn>,
        temperature: Double? = null
    ): ChatCompletion {
        require(provider.apiKey.isNotBlank()) { "Provider API key is missing" }
        require(provider.model.isNotBlank()) { "Provider model is missing" }
        require(turns.isNotEmpty()) { "At least one chat turn is required" }
        require(turns.all { it.role in ALLOWED_ROLES && it.content.isNotBlank() }) {
            "Chat turns must have a supported role and non-empty content"
        }

        val base = URI(provider.baseUrl.trim().trimEnd('/'))
        require(base.scheme.equals("https", ignoreCase = true) && !base.host.isNullOrBlank()) {
            "Provider URL must be a valid HTTPS URL"
        }
        require(base.userInfo == null && base.rawQuery == null && base.rawFragment == null) {
            "Provider URL must not contain credentials, query parameters or fragments"
        }

        val endpoint = base.toASCIIString() + "/chat/completions"
        val request = JSONObject()
            .put("model", provider.model)
            .put("messages", JSONArray().apply {
                turns.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        temperature?.let { request.put("temperature", it) }

        val response = transport.post(endpoint, provider.apiKey, request.toString())
        if (response.statusCode !in 200..299) {
            throw ProviderRequestException(response.statusCode, safeErrorMessage(response.statusCode))
        }

        val root = runCatching { JSONObject(response.body) }
            .getOrElse { throw ProviderResponseException("Provider returned invalid JSON") }
        val content = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: throw ProviderResponseException("Provider response did not contain assistant text")

        return ChatCompletion(text = content, model = root.optString("model", provider.model))
    }

    private fun safeErrorMessage(code: Int): String = when (code) {
        401, 403 -> "Provider rejected the credentials or access (HTTP $code)"
        404 -> "Chat Completions endpoint was not found (HTTP 404)"
        429 -> "Provider rate limit reached (HTTP 429)"
        in 500..599 -> "Provider is temporarily unavailable (HTTP $code)"
        else -> "Provider request failed (HTTP $code)"
    }

    private companion object {
        val ALLOWED_ROLES = setOf("system", "user", "assistant")
    }
}

class ProviderRequestException(val statusCode: Int, message: String) : Exception(message)
class ProviderResponseException(message: String) : Exception(message)
