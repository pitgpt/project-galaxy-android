package com.pitgpt.galaxy.data.remote

import com.pitgpt.galaxy.core.model.RealtimeSessionCredential
import com.pitgpt.galaxy.core.repository.RealtimeSessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class HttpRealtimeSessionProvider(
    baseUrl: String,
    private val accessTokenProvider: GalaxyAccessTokenProvider,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) : RealtimeSessionProvider {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    init {
        val uri = try {
            URI(normalizedBaseUrl)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Galaxy API base URL is invalid", exception)
        }
        val host = uri.host?.lowercase()
        val localHost = host in setOf("127.0.0.1", "10.0.2.2", "localhost")
        require(
            uri.isAbsolute &&
                host != null &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.scheme == "https" || (uri.scheme == "http" && localHost)) &&
                (uri.port == -1 || uri.port in 1..65535)
        ) {
            "Galaxy API base URL must use HTTPS except for exact local development hosts"
        }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    override suspend fun createSession(
        characterId: String,
        voiceId: String?
    ): RealtimeSessionCredential {
        require(characterId.isNotBlank() && characterId.length <= 128)
        require(!voiceId.isNullOrBlank() && voiceId.length <= 128) {
            "A configured voice ID is required"
        }

        val response = request(
            "POST",
            "/v1/realtime/sessions",
            JSONObject().put("character_id", characterId).put("voice_id", voiceId)
        )

        val clientSecret = response.optString("client_secret", "")
        val sessionId = response.optString("session_id", "")
        val expiresAt = response.optLong("expires_at_epoch_millis", -1L)
        if (clientSecret.isBlank() || clientSecret.length > MAX_CLIENT_SECRET_CHARS ||
            sessionId.isBlank() || sessionId.length > MAX_SESSION_ID_CHARS ||
            expiresAt <= 0L
        ) {
            throw GalaxyApiException(null, "Galaxy returned an invalid session response")
        }

        return try {
            RealtimeSessionCredential(clientSecret, expiresAt, sessionId)
        } catch (exception: Exception) {
            throw GalaxyApiException(null, "Galaxy returned an invalid session response")
        }
    }

    override suspend fun heartbeat(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= MAX_SESSION_ID_CHARS)
        request("POST", "/v1/realtime/sessions/" + encodePathSegment(sessionId) + "/heartbeat")
    }

    override suspend fun closeSession(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= MAX_SESSION_ID_CHARS)
        request("DELETE", "/v1/realtime/sessions/" + encodePathSegment(sessionId))
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = try {
            accessTokenProvider.accessToken()?.takeIf { it.isNotBlank() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw GalaxyApiException(null, "Could not retrieve Galaxy authentication")
        } ?: throw GalaxyApiException(401, "Galaxy account authentication is required")

        val connection = try {
            connectionFactory(URL(normalizedBaseUrl + path))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw GalaxyApiException(null, "Could not reach the Galaxy service")
        }

        var requestBytes: ByteArray? = null
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.doInput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + token)

            if (body != null) {
                requestBytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.executeCancellable {
                    connection.outputStream.use { output -> output.write(requestBytes) }
                }
            }

            val status = connection.executeCancellable { connection.responseCode }
            if (status !in 200..299) {
                throw GalaxyApiException(status, safeMessageFor(status))
            }

            val responseText = connection.executeCancellable {
                readBoundedResponse(connection)
            }
            if (responseText.isBlank()) JSONObject() else {
                try {
                    JSONObject(responseText)
                } catch (exception: Exception) {
                    throw GalaxyApiException(null, "Galaxy returned an invalid response")
                }
            }
        } catch (exception: GalaxyApiException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw GalaxyApiException(null, "Could not reach the Galaxy service")
        } finally {
            requestBytes?.fill(0)
            connection.disconnect()
        }
    }

    private fun readBoundedResponse(connection: HttpURLConnection): String {
        val declaredLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_RESPONSE_BYTES) {
            throw GalaxyApiException(null, "Galaxy returned a response that exceeds the size limit")
        }

        val buffer = ByteArray(MAX_RESPONSE_BYTES + 1)
        var total = 0
        try {
            connection.inputStream.use { input ->
                while (total <= MAX_RESPONSE_BYTES) {
                    val count = input.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > MAX_RESPONSE_BYTES) {
                        throw GalaxyApiException(null, "Galaxy returned a response that exceeds the size limit")
                    }
                }
            }
            return String(buffer, 0, total, Charsets.UTF_8)
        } finally {
            buffer.fill(0)
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

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val MAX_CLIENT_SECRET_CHARS = 8 * 1024
        const val MAX_SESSION_ID_CHARS = 128
    }
}

class GalaxyApiException(val httpStatus: Int?, message: String) : Exception(message)
