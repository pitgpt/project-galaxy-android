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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

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

data class LilaHistoryMessage(
    val sequence: Long,
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val providerId: String? = null,
    val modelId: String? = null
) {
    val displayModelLabel: String?
        get() = listOfNotNull(providerId, modelId)
            .joinToString(" · ")
            .take(96)
            .takeIf { it.isNotBlank() }
}

/**
 * Stable across Galaxy and Desktop for the same authenticated owner partition
 * and character. This is an identifier, not an authentication credential.
 */
internal fun stableLilaConversationId(ownerKey: String, characterId: String): String {
    require(ownerKey.isNotBlank() && ownerKey.length <= 128)
    require(characterId.isNotBlank() && characterId.length <= 128)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("lila-conversation-v1\u0000$ownerKey\u0000$characterId".toByteArray(Charsets.UTF_8))
    val uuidBytes = digest.copyOfRange(0, 16)
    uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0f) or 0x50).toByte()
    uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(uuidBytes)
    return UUID(buffer.long, buffer.long).toString()
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
    private val parsedUri: URI? = runCatching { URI(normalizedBaseUrl) }.getOrNull()
    private val host = parsedUri?.host?.lowercase()
    private val configurationError: String? = when {
        parsedUri == null -> "Galaxy API base URL is invalid"
        host.isNullOrBlank() -> "Galaxy API base URL must include a host"
        parsedUri.userInfo != null || parsedUri.query != null || parsedUri.fragment != null ->
            "Galaxy API base URL must not contain credentials, query parameters, or fragments"
        parsedUri.port != -1 && parsedUri.port !in 1..65535 ->
            "Galaxy API base URL port is invalid"
        parsedUri.scheme == "https" -> null
        parsedUri.scheme == "http" && host in setOf("127.0.0.1", "10.0.2.2", "localhost") -> null
        else -> "Galaxy API base URL must use HTTPS except for exact local development hosts"
    }
    val isConfigured: Boolean =
        configurationError == null && host?.endsWith(".invalid") == false

    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
    }

    suspend fun sendTurn(conversationId: String, message: String, idempotencyKey: String): LilaTurnResult =
        withContext(Dispatchers.IO) {
            require(conversationId.isNotBlank() && conversationId.length <= 128)
            require(message.isNotBlank() && message.length <= 12_000)
            require(runCatching { UUID.fromString(idempotencyKey) }.isSuccess)

            configurationError?.let { error ->
                throw GalaxyApiException(null, error)
            }
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
                connection.setRequestProperty("Idempotency-Key", idempotencyKey)
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

    /**
     * Downloads the complete owner-scoped history using bounded cursor pages.
     * No local cache mutation happens here; callers commit only after all pages
     * have been validated and assembled.
     */
    suspend fun loadAllHistory(conversationId: String): List<LilaHistoryMessage> =
        withContext(Dispatchers.IO) {
            if (runCatching { UUID.fromString(conversationId) }.isFailure) {
                throw GalaxyApiException(null, "Идентификатор диалога Lila некорректен.")
            }
            configurationError?.let { error -> throw GalaxyApiException(null, error) }
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
                throw GalaxyApiException(401, "Войди в аккаунт Galaxy, чтобы загрузить историю Лилы.")
            }

            val messages = mutableListOf<LilaHistoryMessage>()
            val seenSequences = mutableSetOf<Long>()
            val seenCursors = mutableSetOf<Long>()
            var cursor: Long? = null
            var previousPageOldest: Long? = null
            var totalBytes = 0L
            var pageCount = 0

            while (true) {
                if (pageCount >= MAX_HISTORY_PAGES) {
                    throw GalaxyApiException(null, "История диалога слишком велика для безопасной синхронизации.")
                }
                val page = loadHistoryPageBlocking(conversationId, token, cursor)
                pageCount++
                if (page.messages.isEmpty()) {
                    if (page.nextBeforeSequence != null || cursor != null) {
                        throw GalaxyApiException(null, "Сервис Lila вернул неполную историю диалога.")
                    }
                    break
                }

                val oldest = page.messages.first().sequence
                val newest = page.messages.last().sequence
                if (previousPageOldest != null && newest >= previousPageOldest) {
                    throw GalaxyApiException(null, "Порядок страниц истории Lila некорректен.")
                }
                if (page.nextBeforeSequence != null && page.nextBeforeSequence != oldest) {
                    throw GalaxyApiException(null, "Курсор истории Lila некорректен.")
                }

                for (message in page.messages) {
                    if (!seenSequences.add(message.sequence)) {
                        throw GalaxyApiException(null, "История Lila содержит повторяющиеся сообщения.")
                    }
                    val encoded = message.content.toByteArray(Charsets.UTF_8)
                    try {
                        totalBytes += encoded.size
                    } finally {
                        encoded.fill(0)
                    }
                    if (messages.size + page.messages.size > MAX_HISTORY_MESSAGES ||
                        totalBytes > MAX_HISTORY_BYTES
                    ) {
                        throw GalaxyApiException(null, "История диалога слишком велика для безопасной синхронизации.")
                    }
                }

                messages.addAll(page.messages)
                previousPageOldest = oldest
                val next = page.nextBeforeSequence ?: break
                if (!seenCursors.add(next)) {
                    throw GalaxyApiException(null, "Сервис Lila вернул повторяющийся курсор истории.")
                }
                cursor = next
            }

            messages.sortedBy { it.sequence }
        }

    private fun loadHistoryPageBlocking(
        conversationId: String,
        token: String,
        beforeSequence: Long?
    ): HistoryPage {
        val query = buildString {
            append("limit=")
            append(HISTORY_PAGE_SIZE)
            if (beforeSequence != null) {
                append("&before_sequence=")
                append(beforeSequence)
            }
        }
        val connection = try {
            connectionFactory(
                URL("$normalizedBaseUrl/v1/lila/conversations/$conversationId/messages?$query")
            )
        } catch (exception: Exception) {
            throw GalaxyApiException(null, "Не удалось подключиться к сервису Galaxy.")
        }

        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")

            val status = connection.responseCode
            if (status !in 200..299) {
                throw GalaxyApiException(status, safeMessageFor(status))
            }
            val payload = try {
                JSONObject(readBounded(connection.inputStream, MAX_HISTORY_PAGE_BYTES))
            } catch (exception: GalaxyApiException) {
                throw exception
            } catch (exception: Exception) {
                throw GalaxyApiException(null, "Сервис Lila вернул некорректную историю.")
            }
            if (payload.optString("conversation_id", "") != conversationId) {
                throw GalaxyApiException(null, "Сервис Lila вернул историю другого диалога.")
            }
            val array = payload.optJSONArray("messages")
                ?: throw GalaxyApiException(null, "Сервис Lila вернул некорректную историю.")
            if (array.length() > HISTORY_PAGE_SIZE) {
                throw GalaxyApiException(null, "Страница истории Lila превышает допустимый размер.")
            }

            val pageMessages = mutableListOf<LilaHistoryMessage>()
            var previousSequence = 0L
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw GalaxyApiException(null, "Сервис Lila вернул некорректную историю.")
                val sequence = item.optLong("sequence", -1L)
                val role = item.optString("role", "")
                val content = item.optString("content", "")
                val createdAt = item.optLong("created_at_epoch_millis", -1L)
                val providerId = optionalString(item, "provider_id", 64)
                val modelId = optionalString(item, "model_id", 256)
                if (sequence <= previousSequence ||
                    role !in setOf("user", "assistant") ||
                    content.isBlank() || content.length > MAX_ASSISTANT_TEXT_CHARS ||
                    createdAt < 0 ||
                    (role == "user" && (providerId != null || modelId != null))
                ) {
                    throw GalaxyApiException(null, "Сервис Lila вернул некорректную историю.")
                }
                pageMessages += LilaHistoryMessage(
                    sequence, role, content, createdAt, providerId, modelId
                )
                previousSequence = sequence
            }

            val nextCursor = if (payload.isNull("next_before_sequence")) {
                null
            } else {
                payload.optLong("next_before_sequence", -1L).takeIf { it > 0 }
                    ?: throw GalaxyApiException(null, "Сервис Lila вернул некорректный курсор истории.")
            }
            if (nextCursor != null &&
                (pageMessages.isEmpty() || nextCursor != pageMessages.first().sequence)
            ) {
                throw GalaxyApiException(null, "Сервис Lila вернул некорректный курсор истории.")
            }
            return HistoryPage(pageMessages, nextCursor)
        } catch (exception: GalaxyApiException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw GalaxyApiException(null, "Не удалось загрузить историю Lila Core.")
        } finally {
            connection.disconnect()
        }
    }

    private data class HistoryPage(
        val messages: List<LilaHistoryMessage>,
        val nextBeforeSequence: Long?
    )

    private fun optionalString(payload: JSONObject, key: String, maxLength: Int): String? {
        if (payload.isNull(key)) return null
        val value = payload.optString(key).takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (value.length > maxLength) {
            throw GalaxyApiException(null, "Сервис Lila вернул некорректные данные модели.")
        }
        return value
    }

    private fun readBounded(input: InputStream, maxBytes: Int = MAX_RESPONSE_BYTES): String {
        val output = ZeroizingByteArrayOutputStream()
        val scratch = ByteArray(8 * 1024)
        try {
            input.use { stream ->
                while (true) {
                    val count = stream.read(scratch)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) {
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
        409 -> "Проверь выбор модели или дождись завершения предыдущей отправки. Для повтора используй то же сообщение."
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
        const val MAX_HISTORY_PAGE_BYTES = 1024 * 1024
        const val HISTORY_PAGE_SIZE = 20
        const val MAX_HISTORY_PAGES = 25
        const val MAX_HISTORY_MESSAGES = 500
        const val MAX_HISTORY_BYTES = 4 * 1024 * 1024
        const val MAX_ASSISTANT_TEXT_CHARS = 12_000
    }
}
