package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class LilaTurnResult(
    val conversationId: String,
    val text: String,
    val providerId: String?,
    val modelId: String?
) {
    val displayModel: String
        get() = listOfNotNull(providerId, modelId)
            .joinToString(" · ")
            .take(96)
            .ifBlank { "модель не указана" }
}

/**
 * Authenticated Galaxy -> Lila Core text-turn client.
 *
 * Provider credentials stay on the backend. The Android client sends only the
 * Supabase access token and the conversation turn; it never receives model API keys.
 */
class HttpLilaTurnProvider(
    baseUrl: String,
    private val accessTokenProvider: GalaxyAccessTokenProvider?,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 125_000,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) {
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    private val host: String
    val isConfigured: Boolean

    init {
        val uri = try {
            URI(normalizedBaseUrl)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Galaxy API base URL is invalid", exception)
        }
        host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Galaxy API base URL must include a host")
        val localHost = host in setOf("127.0.0.1", "10.0.2.2", "localhost")
        require(
            uri.isAbsolute &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.scheme == "https" || (uri.scheme == "http" && localHost))
        ) {
            "Galaxy API base URL must use HTTPS except for exact local development hosts"
        }
        require(uri.port == -1 || uri.port in 1..65535) {
            "Galaxy API base URL port is invalid"
        }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        isConfigured = !host.endsWith(".invalid")
    }

    suspend fun sendTurn(conversationId: String, message: String): LilaTurnResult =
        withContext(Dispatchers.IO) {
            require(conversationId.isNotBlank() && conversationId.length <= 128)
            require(message.isNotBlank() && message.length <= 12_000)

            if (!isConfigured) {
                throw GalaxyApiException(null, "Сервер Lila не настроен в этой сборке Galaxy.")
            }

            val token = try {
                accessTokenProvider?.accessToken()?.takeIf { it.isNotBlank() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw GalaxyApiException(null, "Не удалось проверить авторизацию Galaxy.")
            }
            if (token == null) {
                throw GalaxyApiException(401, "Войди в аккаунт Galaxy, чтобы отправить сообщение Лиле.")
            }

            val connection = try {
                connectionFactory(URL("$normalizedBaseUrl/v1/lila/turns"))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw GalaxyApiException(null, "Не удалось подключиться к сервису Galaxy.")
            }

            var requestBytes: ByteArray? = null
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Authorization", "Bearer $token")
                val body = JSONObject()
                    .put("conversation_id", conversationId)
                    .put("message", message)
                    .toString()
                requestBytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.outputStream.use { output ->
                    output.write(requestBytes)
                }

                val status = connection.responseCode
                if (status !in 200..299) {
                    throw GalaxyApiException(status, safeMessageFor(status))
                }

                val responseText = readBounded(connection.inputStream)
                val payload = try {
                    JSONObject(responseText)
                } catch (exception: Exception) {
                    throw GalaxyApiException(null, "Сервис Lila вернул некорректный ответ.")
                }

                val responseConversationId = payload.optString("conversation_id", "")
                if (responseConversationId != conversationId) {
                    throw GalaxyApiException(null, "Сервис Lila вернул ответ для другого диалога.")
                }
                val assistantText = payload.optString("text", "")
                if (assistantText.isBlank() || assistantText.length > MAX_ASSISTANT_TEXT_CHARS) {
                    throw GalaxyApiException(null, "Сервис Lila вернул пустой или слишком длинный ответ.")
                }

                LilaTurnResult(
                    conversationId = responseConversationId,
                    text = assistantText,
                    providerId = optionalString(payload, "provider_id", 64),
                    modelId = optionalString(payload, "model_id", 256)
                )
            } catch (exception: GalaxyApiException) {
                throw exception
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw GalaxyApiException(null, "Не удалось получить ответ от Lila Core.")
            } finally {
                requestBytes?.fill(0)
                connection.disconnect()
            }
        }

    private fun optionalString(payload: JSONObject, key: String, maxLength: Int): String? {
        if (payload.isNull(key)) return null
        val value = payload.optString(key).takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (value.length > maxLength) {
            throw GalaxyApiException(null, "Сервис Lila вернул некорректные данные модели.")
        }
        return value
    }

    private fun readBounded(input: InputStream): String {
        val output = ZeroizingByteArrayOutputStream()
        val scratch = ByteArray(8 * 1024)
        try {
            input.use { stream ->
                while (true) {
                    val count = stream.read(scratch)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw GalaxyApiException(null, "Ответ Lila превышает допустимый размер.")
                    }
                    output.write(scratch, 0, count)
                }
            }
            return output.toUtf8String()
        } finally {
            scratch.fill(0)
            output.wipe()
        }
    }

    private fun safeMessageFor(status: Int): String = when (status) {
        301, 302, 303, 307, 308 -> "Сервис Galaxy перенаправил запрос. Проверь HTTPS-адрес backend."
        401 -> "Сессия Galaxy истекла. Войди снова."
        403 -> "У этого аккаунта нет доступа к диалогу."
        404 -> "Маршрут Lila Core не найден на backend."
        422 -> "Сообщение не прошло проверку Lila Core."
        429 -> "Достигнут лимит сообщений. Попробуй позже."
        502 -> "Провайдер модели вернул некорректный ответ."
        in 500..599 -> "Lila Core или модель сейчас недоступны."
        else -> "Запрос Galaxy завершился ошибкой (HTTP $status)."
    }

    private class ZeroizingByteArrayOutputStream : ByteArrayOutputStream() {
        fun toUtf8String(): String = String(buf, 0, count, Charsets.UTF_8)

        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 128 * 1024
        const val MAX_ASSISTANT_TEXT_CHARS = 12_000
    }
}
