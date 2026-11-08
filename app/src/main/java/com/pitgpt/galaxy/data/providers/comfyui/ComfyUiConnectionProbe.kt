package com.pitgpt.galaxy.data.providers.comfyui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resumeWith

data class ComfyUiHttpResponse(
    val statusCode: Int,
    val body: String
)

/** Small transport seam for deterministic tests and alternative HTTP implementations. */
fun interface ComfyUiHttpTransport {
    suspend fun get(
        url: String,
        bearerToken: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseBytes: Int
    ): ComfyUiHttpResponse
}

class UrlConnectionComfyUiTransport : ComfyUiHttpTransport {
    override suspend fun get(
        url: String,
        bearerToken: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseBytes: Int
    ): ComfyUiHttpResponse = suspendCancellableCoroutine { continuation ->
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

        continuation.invokeOnCancellation { connection.disconnect() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) {
                connection.disconnect()
                return@Runnable
            }
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { readLimitedUtf8(it, maxResponseBytes) }.orEmpty()
                continuation.resumeWith(Result.success(ComfyUiHttpResponse(status, body)))
            } catch (error: Exception) {
                continuation.resumeWith(Result.failure(error))
            } finally {
                connection.disconnect()
            }
        })
    }

    private fun readLimitedUtf8(input: java.io.InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("ComfyUI response exceeded the size limit")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

data class ComfyUiProbeResult(
    val reachable: Boolean,
    val httpStatusCode: Int? = null,
    val message: String
)

/** Probes ComfyUI's system endpoint without exposing response bodies or credentials. */
class ComfyUiConnectionProbe(
    private val transport: ComfyUiHttpTransport = UrlConnectionComfyUiTransport()
) {
    suspend operator fun invoke(config: ComfyUiConnectionConfig): ComfyUiProbeResult {
        val response = try {
            transport.get(
                url = config.endpoint("/system_stats"),
                bearerToken = config.bearerToken,
                connectTimeoutMillis = config.connectTimeoutMillis,
                readTimeoutMillis = config.readTimeoutMillis,
                maxResponseBytes = MAX_RESPONSE_BYTES
            )
        } catch (_: Exception) {
            return ComfyUiProbeResult(
                reachable = false,
                message = "Unable to reach the configured ComfyUI endpoint"
            )
        }

        if (response.statusCode !in 200..299) {
            val message = when (response.statusCode) {
                401, 403 -> "ComfyUI rejected the configured access token"
                404 -> "ComfyUI system endpoint was not found"
                in 500..599 -> "ComfyUI is temporarily unavailable"
                else -> "ComfyUI connection failed (HTTP ${response.statusCode})"
            }
            return ComfyUiProbeResult(false, response.statusCode, message)
        }

        val validJson = runCatching { JSONObject(response.body) }.isSuccess
        if (!validJson) {
            return ComfyUiProbeResult(
                reachable = false,
                httpStatusCode = response.statusCode,
                message = "The endpoint responded, but did not return valid ComfyUI JSON"
            )
        }

        return ComfyUiProbeResult(
            reachable = true,
            httpStatusCode = response.statusCode,
            message = "ComfyUI is reachable"
        )
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}
