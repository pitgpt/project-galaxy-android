package com.pitgpt.galaxy.data.remote

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential
import com.pitgpt.galaxy.core.repository.RealtimeSessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpRealtimeSessionProvider(
    baseUrl: String,
    private val accessTokenProvider: GalaxyAccessTokenProvider,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000
) : RealtimeSessionProvider {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    init {
        require(normalizedBaseUrl.startsWith("https://") ||
            normalizedBaseUrl.startsWith("http://127.0.0.1") ||
            normalizedBaseUrl.startsWith("http://10.0.2.2")) {
            "Galaxy API base URL must use HTTPS except for local development"
        }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    override suspend fun createSession(characterId: String, voiceId: String?): RealtimeSessionCredential {
        require(characterId.isNotBlank())
        require(!voiceId.isNullOrBlank()) { "A configured voice ID is required" }
        val body = JSONObject().put("character_id", characterId).put("voice_id", voiceId)
        val response = request("POST", "/v1/realtime/sessions", body)
        return try {
            RealtimeSessionCredential(
                response.getString("client_secret"),
                response.getLong("expires_at_epoch_millis"),
                response.getString("session_id")
            )
        } catch (exception: Exception) {
            throw GalaxyApiException(null, "Galaxy returned an invalid session response")
        }
    }

    override suspend fun heartbeat(sessionId: String) {
        require(sessionId.isNotBlank())
        request("POST", "/v1/realtime/sessions/" + encodePathSegment(sessionId) + "/heartbeat")
    }

    override suspend fun closeSession(sessionId: String) {
        require(sessionId.isNotBlank())
        request("DELETE", "/v1/realtime/sessions/" + encodePathSegment(sessionId))
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val token = accessTokenProvider.accessToken()?.takeIf { it.isNotBlank() }
                ?: throw GalaxyApiException(401, "Galaxy account authentication is required")
            val connection = URL(normalizedBaseUrl + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer " + token)
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw GalaxyApiException(status, safeMessageFor(status))
                val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            } catch (exception: GalaxyApiException) {
                throw exception
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw GalaxyApiException(null, "Could not reach the Galaxy service")
            } finally {
                connection.disconnect()
            }
        }

    private fun safeMessageFor(status: Int): String = when (status) {
        401 -> "Galaxy account authentication is required"
        403 -> "This Galaxy account is not permitted to create this session"
        404 -> "Realtime session was not found or has expired"
        422 -> "The requested character or voice is not available"
        429 -> "Realtime session limit reached; retry later"
        in 500..599 -> "Galaxy service is temporarily unavailable"
        else -> "Galaxy request failed (HTTP " + status + ")"
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

class GalaxyApiException(val httpStatus: Int?, message: String) : Exception(message)
