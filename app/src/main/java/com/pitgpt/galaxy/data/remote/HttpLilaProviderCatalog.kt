package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

data class LilaProviderSummary(
    val providerId: String,
    val providerType: String,
    val modelId: String,
    val capabilities: List<String>,
    val timeoutSeconds: Double,
    val enabled: Boolean,
    val hasApiKey: Boolean
)

data class LilaProviderProbeResult(
    val providerId: String,
    val configuredModelId: String,
    val connectionReachable: Boolean,
    val modelDiscoverySupported: Boolean,
    val configuredModelAvailable: Boolean?,
    val modelIds: List<String>,
    val chatCompletionVerified: Boolean
)

data class LilaProviderChatTestResult(
    val providerId: String,
    val modelId: String,
    val chatCompletionVerified: Boolean,
    val responseCharacters: Int
)

data class LilaConversationModelSelection(
    val conversationId: String,
    val selected: Boolean,
    val providerId: String?,
    val modelId: String?,
    val fallbackProviderId: String? = null,
    val fallbackModelId: String? = null
)

data class LilaRequestDiagnostic(
    val requestId: String,
    val operation: String,
    val category: String,
    val httpStatus: Int?
)

/**
 * Authenticated client for the owner-scoped Lila provider registry.
 * API keys are sent only to the configured Galaxy backend and are never
 * written to Android preferences, Room, or client-side logs.
 */
class HttpLilaProviderCatalog(
    baseUrl: String,
    private val accessTokenProvider: GalaxyAccessTokenProvider?,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 20_000,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val requestIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val diagnosticObserver: (LilaRequestDiagnostic) -> Unit = {}
) : LilaImageApiTransport {
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

    suspend fun listProviders(): List<LilaProviderSummary> {
        val payload = requestJson("GET", "/v1/lila/providers")
        val array = payload.optJSONArray("providers")
            ?: throw GalaxyApiException(null, "Сервис Lila вернул некорректный каталог провайдеров.")
        if (array.length() > MAX_PROVIDERS) {
            throw GalaxyApiException(null, "Каталог провайдеров превышает допустимый размер.")
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw GalaxyApiException(null, "Сервис Lila вернул некорректный каталог.")
                add(parseProvider(item))
            }
        }
    }

    suspend fun createProvider(
        providerId: String,
        providerType: String,
        baseUrl: String,
        modelId: String,
        apiKey: String?,
        timeoutSeconds: Double,
        enabled: Boolean = true
    ): LilaProviderSummary {
        val body = providerBody(providerType, baseUrl, modelId, apiKey, timeoutSeconds, enabled)
            .put("provider_id", providerId)
        return parseProvider(requestJson("POST", "/v1/lila/providers", body))
    }

    suspend fun updateProvider(
        providerId: String,
        providerType: String,
        baseUrl: String,
        modelId: String,
        apiKey: String?,
        clearApiKey: Boolean,
        timeoutSeconds: Double,
        enabled: Boolean
    ): LilaProviderSummary {
        require(!(clearApiKey && !apiKey.isNullOrBlank()))
        val body = providerBody(providerType, baseUrl, modelId, apiKey, timeoutSeconds, enabled)
            .put("clear_api_key", clearApiKey)
        return parseProvider(
            requestJson("PUT", "/v1/lila/providers/${encodePathSegment(providerId)}", body)
        )
    }

    suspend fun deleteProvider(providerId: String) {
        requestJson("DELETE", "/v1/lila/providers/${encodePathSegment(providerId)}")
    }

    suspend fun probeProvider(providerId: String): LilaProviderProbeResult {
        val payload = requestJson(
            "POST",
            "/v1/lila/providers/${encodePathSegment(providerId)}/probe"
        )
        val ids = payload.optJSONArray("model_ids")
            ?: throw GalaxyApiException(null, "Сервис Lila вернул некорректный список моделей.")
        if (ids.length() > MAX_MODELS) {
            throw GalaxyApiException(null, "Список моделей превышает допустимый размер.")
        }
        val modelIds = buildList {
            for (index in 0 until ids.length()) {
                val value = ids.optString(index, "")
                if (value.isBlank() || value.length > MAX_MODEL_ID_CHARS ||
                    value.any { it.code < 32 || it.code == 127 }
                ) {
                    throw GalaxyApiException(null, "Сервис Lila вернул некорректный идентификатор модели.")
                }
                add(value)
            }
        }
        val available = if (payload.isNull("configured_model_available")) {
            null
        } else {
            payload.optBoolean("configured_model_available")
        }
        return LilaProviderProbeResult(
            providerId = payload.optString("provider_id", ""),
            configuredModelId = payload.optString("configured_model_id", ""),
            connectionReachable = payload.optBoolean("connection_reachable"),
            modelDiscoverySupported = payload.optBoolean("model_discovery_supported"),
            configuredModelAvailable = available,
            modelIds = modelIds,
            chatCompletionVerified = payload.optBoolean("chat_completion_verified", false)
        )
    }

    suspend fun testProviderChat(providerId: String): LilaProviderChatTestResult {
        val payload = requestJson(
            "POST",
            "/v1/lila/providers/${encodePathSegment(providerId)}/test-chat"
        )
        val returnedProviderId = payload.optString("provider_id", "")
        val modelId = payload.optString("model_id", "")
        val verified = payload.optBoolean("chat_completion_verified")
        val responseCharacters = payload.optInt("response_characters", 0)
        if (returnedProviderId != providerId ||
            modelId.isBlank() || modelId.length > MAX_MODEL_ID_CHARS ||
            !verified || responseCharacters !in 1..MAX_ASSISTANT_TEST_RESPONSE_CHARS
        ) {
            throw GalaxyApiException(null, "Сервис Lila не подтвердил тестовую генерацию.")
        }
        return LilaProviderChatTestResult(
            providerId = returnedProviderId,
            modelId = modelId,
            chatCompletionVerified = verified,
            responseCharacters = responseCharacters
        )
    }

    suspend fun getConversationModel(conversationId: String): LilaConversationModelSelection {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        val payload = requestJson(
            "GET",
            "/v1/lila/conversations/${encodePathSegment(conversationId)}/model"
        )
        return parseSelection(payload, conversationId)
    }

    suspend fun setConversationModel(
        conversationId: String,
        providerId: String,
        modelId: String,
        fallbackProviderId: String? = null,
        fallbackModelId: String? = null
    ): LilaConversationModelSelection {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        require(providerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(modelId.isNotBlank() && modelId.length <= MAX_MODEL_ID_CHARS)
        require((fallbackProviderId == null) == (fallbackModelId == null))
        if (fallbackProviderId != null) {
            require(fallbackProviderId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
            require(fallbackProviderId != providerId)
            require(!fallbackModelId.isNullOrBlank() && fallbackModelId.length <= MAX_MODEL_ID_CHARS)
        }
        val body = JSONObject()
            .put("provider_id", providerId)
            .put("model_id", modelId)
            .put("fallback_provider_id", fallbackProviderId ?: JSONObject.NULL)
            .put("fallback_model_id", fallbackModelId ?: JSONObject.NULL)
        val payload = requestJson(
            "PUT",
            "/v1/lila/conversations/${encodePathSegment(conversationId)}/model",
            body
        )
        return parseSelection(payload, conversationId)
    }

    private fun providerBody(
        providerType: String,
        baseUrl: String,
        modelId: String,
        apiKey: String?,
        timeoutSeconds: Double,
        enabled: Boolean
    ): JSONObject {
        require(providerType in setOf("cloud", "local"))
        require(baseUrl.isNotBlank() && baseUrl.length <= 2048)
        require(modelId.isNotBlank() && modelId.length <= MAX_MODEL_ID_CHARS)
        require(timeoutSeconds in 1.0..120.0)
        val body = JSONObject()
            .put("provider_type", providerType)
            .put("base_url", baseUrl.trim())
            .put("model_id", modelId)
            .put("timeout_seconds", timeoutSeconds)
            .put("capabilities", JSONArray().put("chat"))
            .put("enabled", enabled)
        if (!apiKey.isNullOrBlank()) body.put("api_key", apiKey)
        return body
    }

    override suspend fun requestJson(
        method: String,
        path: String,
        body: JSONObject?,
        idempotencyKey: String?,
        maxResponseBytes: Int,
        maxRequestBytes: Int
    ): JSONObject = withContext(Dispatchers.IO) {
        configurationError?.let { throw GalaxyApiException(null, it) }
        if (!isConfigured) {
            throw GalaxyApiException(null, "Сервер Lila не настроен в этой сборке Galaxy.")
        }
        val token = try {
            accessTokenProvider?.accessToken()?.takeIf { it.isNotBlank() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw GalaxyApiException(null, "Не удалось проверить авторизацию Galaxy.")
        } ?: throw GalaxyApiException(401, "Войди в аккаунт Galaxy для управления провайдерами.")

        require(maxResponseBytes in 1..MAX_IMAGE_JSON_RESPONSE_BYTES)
        require(maxRequestBytes in 1..MAX_IMAGE_JSON_REQUEST_BYTES)
        idempotencyKey?.let { key ->
            try { UUID.fromString(key) } catch (_: IllegalArgumentException) {
                throw GalaxyApiException(null, "Идентификатор запроса изображения некорректен.")
            }
        }
        val requestId = requestIdProvider().takeIf { value ->
            value.length in 1..128 && value.all { it.code in 33..126 }
        } ?: UUID.randomUUID().toString()
        val operation = diagnosticOperation(method, path)
        val connection = try {
            connectionFactory(URL(normalizedBaseUrl + path))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportDiagnostic(
                LilaRequestDiagnostic(requestId, operation, diagnosticCategory(exception), null)
            )
            throw GalaxyApiException(null, "Не удалось подключиться к сервису Galaxy.")
        }
        var requestBytes: ByteArray? = null
        var responseStatus: Int? = null
        var diagnosticReported = false
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = body != null
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Request-ID", requestId)
            connection.setRequestProperty("Authorization", "Bearer $token")
            idempotencyKey?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if (body != null) {
                requestBytes = body.toString().toByteArray(Charsets.UTF_8)
                if (requestBytes.size > maxRequestBytes) {
                    requestBytes.fill(0)
                    requestBytes = null
                    throw GalaxyApiException(null, "Запрос к Lila превышает допустимый размер.")
                }
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.executeCancellable { connection.outputStream.use { output -> output.write(requestBytes) } }
            }
            val status = connection.executeCancellable { connection.responseCode }
            responseStatus = status
            if (status !in 200..299) {
                val retrySafe = connection.getHeaderField("X-Lila-Retry-Safe")
                    ?.equals("true", ignoreCase = true) == true
                val outcomeUnknown = connection.getHeaderField("X-Lila-Outcome-Unknown")
                    ?.equals("true", ignoreCase = true) == true
                val message = when {
                    retrySafe -> "Соединение не установлено до отправки запроса. Повтор безопасен."
                    outcomeUnknown -> "Провайдер мог получить тестовый запрос. Повтор может снова расходовать токены."
                    else -> safeMessageFor(status)
                }
                reportDiagnostic(LilaRequestDiagnostic(requestId, operation, "http", status))
                diagnosticReported = true
                throw GalaxyApiException(status, message)
            }
            val responseText = connection.executeCancellable { readBounded(connection.inputStream, maxResponseBytes) }
            if (responseText.isBlank()) JSONObject() else try {
                JSONObject(responseText)
            } catch (exception: Exception) {
                throw GalaxyApiException(null, "Сервис Lila вернул некорректный JSON.")
            }
        } catch (exception: GalaxyApiException) {
            if (!diagnosticReported) {
                reportDiagnostic(
                    LilaRequestDiagnostic(
                        requestId,
                        operation,
                        if (exception.httpStatus != null) "http" else "invalid_response",
                        responseStatus ?: exception.httpStatus
                    )
                )
            }
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (!diagnosticReported) {
                reportDiagnostic(
                    LilaRequestDiagnostic(
                        requestId,
                        operation,
                        diagnosticCategory(exception),
                        responseStatus
                    )
                )
            }
            throw GalaxyApiException(null, "Не удалось выполнить запрос к Lila Core.")
        } finally {
            requestBytes?.fill(0)
            connection.disconnect()
        }
    }

    override suspend fun requestBinary(path: String, maxResponseBytes: Int, expectedContentType: String): LilaBinaryResponse =
        withContext(Dispatchers.IO) {
            require(maxResponseBytes in 1..MAX_BINARY_RESPONSE_BYTES)
            require(expectedContentType.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")))
            configurationError?.let { throw GalaxyApiException(null, it) }
            if (!isConfigured) throw GalaxyApiException(null, "Сервер Lila не настроен в этой сборке Galaxy.")
            val token = try {
                accessTokenProvider?.accessToken()?.takeIf { it.isNotBlank() }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                throw GalaxyApiException(null, "Не удалось проверить авторизацию Galaxy.")
            } ?: throw GalaxyApiException(401, "Войди в аккаунт Galaxy для загрузки изображений.")
            val requestId = requestIdProvider().takeIf { value ->
                value.length in 1..128 && value.all { it.code in 33..126 }
            } ?: UUID.randomUUID().toString()
            val operation = diagnosticOperation("GET", path)
            val connection = try {
                connectionFactory(URL(normalizedBaseUrl + path))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                reportDiagnostic(LilaRequestDiagnostic(requestId, operation, diagnosticCategory(exception), null))
                throw GalaxyApiException(null, "Не удалось подключиться к сервису Galaxy.")
            }
            var responseStatus: Int? = null
            var responseBytes: ByteArray? = null
            var handedOff = false
            var diagnosticReported = false
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = false
                connection.setRequestProperty("Accept", expectedContentType)
                connection.setRequestProperty("X-Request-ID", requestId)
                connection.setRequestProperty("Authorization", "Bearer $token")
                val status = connection.executeCancellable { connection.responseCode }
                responseStatus = status
                if (status !in 200..299) {
                    reportDiagnostic(LilaRequestDiagnostic(requestId, operation, "http", status))
                    diagnosticReported = true
                    throw GalaxyApiException(status, safeMessageFor(status))
                }
                val contentType = connection.getHeaderField("Content-Type")
                    ?.substringBefore(';')?.trim()?.lowercase()
                    ?: throw GalaxyApiException(null, "Сервис Lila не указал формат изображения.")
                if (contentType != expectedContentType.lowercase()) {
                    throw GalaxyApiException(null, "Сервис Lila вернул неподдерживаемый формат изображения.")
                }
                val declaredLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
                if (declaredLength != null && (declaredLength < 0 || declaredLength > maxResponseBytes)) {
                    throw GalaxyApiException(null, "Изображение Lila превышает допустимый размер.")
                }
                val bytes = connection.executeCancellable { readBoundedBytes(connection.inputStream, maxResponseBytes) }
                responseBytes = bytes
                if (declaredLength != null && declaredLength != bytes.size.toLong()) {
                    throw GalaxyApiException(null, "Сервис Lila вернул неполный файл изображения.")
                }
                reportDiagnostic(LilaRequestDiagnostic(requestId, operation, "http", status))
                diagnosticReported = true
                handedOff = true
                LilaBinaryResponse(contentType, bytes)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: GalaxyApiException) {
                if (!diagnosticReported) reportDiagnostic(
                    LilaRequestDiagnostic(requestId, operation,
                        if (exception.httpStatus != null) "http" else "invalid-response", responseStatus)
                )
                throw exception
            } catch (exception: Exception) {
                if (!diagnosticReported) reportDiagnostic(
                    LilaRequestDiagnostic(requestId, operation, diagnosticCategory(exception), responseStatus)
                )
                throw GalaxyApiException(null, "Не удалось загрузить изображение из Lila Core.")
            } finally {
                if (!handedOff) responseBytes?.fill(0)
                connection.disconnect()
            }
        }

    private fun reportDiagnostic(diagnostic: LilaRequestDiagnostic) {
        runCatching { diagnosticObserver(diagnostic) }
    }

    private fun diagnosticOperation(method: String, path: String): String = when {
        method == "POST" && path == "/v1/lila/providers" -> "create-provider"
        method == "POST" && Regex("/v1/lila/providers/[^/]+/probe").matches(path) ->
            "probe-provider"
        method == "PUT" && Regex("/v1/lila/conversations/[^/]+/model").matches(path) ->
            "select-conversation-model"
        method == "GET" && Regex("/v1/lila/conversations/[^/]+/model").matches(path) ->
            "get-conversation-model"
        method == "POST" && path == "/v1/lila/images" -> "submit-image"
        method == "GET" && Regex("/v1/lila/images/[0-9a-fA-F-]{36}").matches(path) -> "get-image-job"
        method == "POST" && Regex("/v1/lila/images/[0-9a-fA-F-]{36}/cancel").matches(path) -> "cancel-image-job"
        method == "GET" && Regex("/v1/lila/images/[0-9a-fA-F-]{36}/asset").matches(path) -> "download-image-asset"
        method == "GET" && path == "/v1/lila/image-providers/comfyui" -> "get-image-provider"
        method == "GET" && path == "/v1/lila/image-providers/comfyui/config" -> "get-image-provider-config"
        method == "GET" && path.startsWith("/v1/lila/images?") -> "list-image-history"
        method == "PUT" && path == "/v1/lila/image-providers/comfyui" -> "configure-image-provider"
        method == "POST" && path == "/v1/lila/image-providers/comfyui/test" -> "test-image-provider"
        else -> "other"
    }

    private fun diagnosticCategory(exception: Exception): String = when (exception) {
        is SSLException -> "tls"
        is UnknownHostException -> "dns"
        is SocketTimeoutException -> "timeout"
        is ConnectException -> "connection"
        is SocketException -> "socket"
        is IOException -> "io"
        else -> "other"
    }

    private fun parseProvider(payload: JSONObject): LilaProviderSummary {
        val providerId = payload.optString("provider_id", "")
        val providerType = payload.optString("provider_type", "")
        val modelId = payload.optString("model_id", "")
        if (!providerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) ||
            providerType !in setOf("cloud", "local") ||
            modelId.isBlank() || modelId.length > MAX_MODEL_ID_CHARS
        ) {
            throw GalaxyApiException(null, "Сервис Lila вернул некорректные данные провайдера.")
        }
        val capabilities = payload.optJSONArray("capabilities") ?: JSONArray()
        val parsedCapabilities = buildList {
            for (index in 0 until capabilities.length()) add(capabilities.optString(index))
        }
        return LilaProviderSummary(
            providerId = providerId,
            providerType = providerType,
            modelId = modelId,
            capabilities = parsedCapabilities,
            timeoutSeconds = payload.optDouble("timeout_seconds", 30.0),
            enabled = payload.optBoolean("enabled"),
            hasApiKey = payload.optBoolean("has_api_key")
        )
    }

    private fun parseSelection(
        payload: JSONObject,
        expectedConversationId: String
    ): LilaConversationModelSelection {
        if (payload.optString("conversation_id", "") != expectedConversationId) {
            throw GalaxyApiException(null, "Сервис Lila вернул выбор модели для другого диалога.")
        }
        val selected = payload.optBoolean("selected")
        val providerId = optionalString(payload, "provider_id", 64)
        val modelId = optionalString(payload, "model_id", MAX_MODEL_ID_CHARS)
        val fallbackProviderId = optionalString(payload, "fallback_provider_id", 64)
        val fallbackModelId = optionalString(payload, "fallback_model_id", MAX_MODEL_ID_CHARS)
        if (selected && (providerId == null || modelId == null)) {
            throw GalaxyApiException(null, "Сервис Lila вернул неполный выбор модели.")
        }
        if ((fallbackProviderId == null) != (fallbackModelId == null) ||
            (fallbackProviderId != null && (!selected || fallbackProviderId == providerId))
        ) {
            throw GalaxyApiException(null, "Сервис Lila вернул некорректный резервный выбор модели.")
        }
        return LilaConversationModelSelection(
            conversationId = expectedConversationId,
            selected = selected,
            providerId = providerId,
            modelId = modelId,
            fallbackProviderId = fallbackProviderId,
            fallbackModelId = fallbackModelId
        )
    }

    private fun optionalString(payload: JSONObject, key: String, maxLength: Int): String? {
        if (payload.isNull(key)) return null
        val value = payload.optString(key).takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (value.length > maxLength) {
            throw GalaxyApiException(null, "Сервис Lila вернул некорректные данные модели.")
        }
        return value
    }

    private fun readBounded(input: InputStream, maxBytes: Int): String {
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

    private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val output = ZeroizingByteArrayOutputStream()
        val scratch = ByteArray(8 * 1024)
        try {
            input.use { stream ->
                while (true) {
                    val count = stream.read(scratch)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) {
                        throw GalaxyApiException(null, "Изображение Lila превышает допустимый размер.")
                    }
                    output.write(scratch, 0, count)
                }
            }
            return output.toByteArrayAndWipe()
        } finally {
            scratch.fill(0)
            output.wipe()
        }
    }

    private fun safeMessageFor(status: Int): String = when (status) {
        400, 422 -> "Проверь адрес, тип провайдера и идентификатор модели."
        401 -> "Сессия Galaxy истекла. Войди снова."
        403 -> "У аккаунта нет доступа к этому провайдеру."
        404 -> "Провайдер или маршрут Lila Core не найден."
        409 -> "Провайдер уже существует, отключён или требует нового выбора модели."
        429 -> "Достигнут лимит запросов к Lila. Попробуй позже."
        502 -> "Провайдер модели вернул некорректный ответ."
        in 500..599 -> "Lila Core временно недоступен."
        else -> "Запрос к Lila завершился ошибкой (HTTP $status)."
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private class ZeroizingByteArrayOutputStream : ByteArrayOutputStream() {
        fun toUtf8String(): String = String(buf, 0, count, Charsets.UTF_8)
        fun toByteArrayAndWipe(): ByteArray = toByteArray().also { wipe() }
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 128 * 1024
        const val MAX_IMAGE_JSON_RESPONSE_BYTES = 5 * 1024 * 1024
        const val MAX_IMAGE_JSON_REQUEST_BYTES = 5 * 1024 * 1024
        const val MAX_BINARY_RESPONSE_BYTES = 25 * 1024 * 1024
        const val MAX_PROVIDERS = 20
        const val MAX_MODELS = 100
        const val MAX_MODEL_ID_CHARS = 256
        const val MAX_ASSISTANT_TEST_RESPONSE_CHARS = 12_000
    }
}
