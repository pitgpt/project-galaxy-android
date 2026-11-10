package com.pitgpt.galaxy.data.providers.comfyui

import com.pitgpt.galaxy.core.model.ImageGenerationRequest
import com.pitgpt.galaxy.core.model.ImageGenerationState
import com.pitgpt.galaxy.core.model.ImageGenerationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

data class ComfyUiApiResponse(val statusCode: Int, val body: String) {
    /** API error/history bodies can contain user prompts or workflow details; never stringify them. */
    override fun toString(): String =
        "ComfyUiApiResponse(statusCode=$statusCode, bodyChars=${body.length})"
}

data class ComfyUiBinaryResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray
)

/** HTTP boundary for ComfyUI API calls. Implementations must disable redirects and bound reads. */
interface ComfyUiApiTransport {
    suspend fun execute(
        method: String,
        url: String,
        body: ByteArray?,
        bearerToken: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseBytes: Int
    ): ComfyUiApiResponse

    suspend fun getBinary(
        url: String,
        bearerToken: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseBytes: Int
    ): ComfyUiBinaryResponse
}

/** Redirect-free URLConnection transport used for JSON API requests and bounded image downloads. */
class UrlConnectionComfyUiApiTransport : ComfyUiApiTransport {
    override suspend fun execute(
        method: String,
        url: String,
        body: ByteArray?,
        bearerToken: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseBytes: Int
    ): ComfyUiApiResponse {
        require(method == "GET" || method == "POST") { "Unsupported ComfyUI HTTP method" }
        require(body == null || body.size <= MAX_REQUEST_BYTES) { "ComfyUI request is too large" }
        return withConnection(url, method, body, bearerToken, connectTimeoutMillis, readTimeoutMillis) { connection ->
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { readLimited(it, maxResponseBytes) } ?: ByteArray(0)
            ComfyUiApiResponse(status, String(bytes, StandardCharsets.UTF_8))
        }
    }

    override suspend fun getBinary(
        url: String,
        bearerToken: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxResponseBytes: Int
    ): ComfyUiBinaryResponse = withConnection(
        url, "GET", null, bearerToken, connectTimeoutMillis, readTimeoutMillis
    ) { connection ->
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { readLimited(it, maxResponseBytes) } ?: ByteArray(0)
        ComfyUiBinaryResponse(status, connection.contentType, bytes)
    }

    private suspend fun <T> withConnection(
        url: String,
        method: String,
        body: ByteArray?,
        bearerToken: String?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        block: (HttpURLConnection) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json, image/png, image/jpeg, image/webp")
            bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.size)
            }
        }

        continuation.invokeOnCancellation { connection.disconnect() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) {
                connection.disconnect()
                return@Runnable
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body) }
                continuation.resumeWith(Result.success(block(connection)))
            } catch (error: Exception) {
                continuation.resumeWith(Result.failure(error))
            } finally {
                connection.disconnect()
            }
        })
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        require(maxBytes in 1..MAX_BINARY_RESPONSE_BYTES) { "Invalid ComfyUI response size limit" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("ComfyUI response exceeded the size limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
        const val MAX_BINARY_RESPONSE_BYTES = 25 * 1024 * 1024
    }
}

data class ComfyUiNodeInput(val nodeId: String, val inputName: String) {
    init {
        require(nodeId.matches(Regex("[0-9]{1,12}"))) { "ComfyUI node ID is invalid" }
        require(inputName.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "ComfyUI input name is invalid" }
    }
}

/**
 * A versioned, explicitly mapped ComfyUI API-format workflow.
 * No node IDs or input names are guessed by the adapter.
 */
data class ComfyUiWorkflowTemplate(
    val id: String,
    val workflowJson: String,
    val positivePrompt: ComfyUiNodeInput,
    val negativePrompt: ComfyUiNodeInput? = null,
    val width: ComfyUiNodeInput? = null,
    val height: ComfyUiNodeInput? = null,
    val seed: ComfyUiNodeInput? = null
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,96}"))) { "Workflow ID is invalid" }
        require(workflowJson.isNotBlank() && workflowJson.length <= MAX_WORKFLOW_JSON_CHARS) {
            "Workflow JSON is empty or too large"
        }
        require(runCatching { JSONObject(workflowJson) }.isSuccess) {
            "Workflow JSON must be a valid object"
        }
    }

    /** Validates every explicitly mapped node and input before a template is persisted. */
    fun validateMappings() {
        validateMappings(JSONObject(workflowJson))
    }

    fun buildPrompt(request: ImageGenerationRequest): JSONObject {
        require(request.workflowId == null || request.workflowId == id) {
            "The request references a different workflow"
        }
        val graph = JSONObject(workflowJson)
        validateMappings(graph)
        setInput(graph, positivePrompt, request.prompt)
        negativePrompt?.let { setInput(graph, it, request.negativePrompt.orEmpty()) }
        width?.let { setInput(graph, it, request.width) }
        height?.let { setInput(graph, it, request.height) }
        if (request.seed != null && seed != null) setInput(graph, seed, request.seed)
        return graph
    }

    private fun validateMappings(graph: JSONObject) {
        listOfNotNull(positivePrompt, negativePrompt, width, height, seed)
            .forEach { mappedInputs(graph, it) }
    }

    private fun setInput(graph: JSONObject, target: ComfyUiNodeInput, value: Any) {
        mappedInputs(graph, target).put(target.inputName, value)
    }

    private fun mappedInputs(graph: JSONObject, target: ComfyUiNodeInput): JSONObject {
        val node = graph.optJSONObject(target.nodeId)
            ?: throw IllegalArgumentException("Workflow is missing a mapped node")
        val inputs = node.optJSONObject("inputs")
            ?: throw IllegalArgumentException("Workflow mapped node has no inputs object")
        require(inputs.has(target.inputName)) { "Workflow is missing a mapped input" }
        return inputs
    }

    /** Avoid exposing user-controlled workflow IDs or imported graph metadata in logs. */
    override fun toString(): String =
        "ComfyUiWorkflowTemplate(configured=${id.isNotBlank()}, workflowJsonChars=${workflowJson.length}, " +
            "positivePromptConfigured=true, negativePromptConfigured=${negativePrompt != null}, " +
            "widthConfigured=${width != null}, heightConfigured=${height != null}, " +
            "seedConfigured=${seed != null})"

    companion object {
        const val MAX_WORKFLOW_JSON_CHARS = 2 * 1024 * 1024
    }
}

data class ComfyUiQueueSnapshot(
    val runningPromptIds: Set<String>,
    val pendingPromptIds: Set<String>
)

data class ComfyUiImageReference(
    val filename: String,
    val subfolder: String = "",
    val type: String = "output"
) {
    init {
        require(filename.isNotBlank() && filename.length <= 255) { "Image filename is invalid" }
        require(filename != "." && filename != "..") { "Image filename is invalid" }
        require(filename.none { it == '/' || it == '\\' || it.code < 0x20 }) {
            "Image filename must not contain path separators or control characters"
        }
        require(subfolder.length <= 1_024 && !subfolder.startsWith("/") && !subfolder.contains('\\')) {
            "Image subfolder is invalid"
        }
        require(subfolder.split('/').none { it == ".." || it == "." }) {
            "Image subfolder contains an unsafe path segment"
        }
        require(type in setOf("input", "output", "temp")) { "ComfyUI image type is invalid" }
    }
}

data class ComfyUiHistoryResult(
    val promptId: String,
    val status: ImageGenerationStatus,
    val images: List<ComfyUiImageReference>
)

data class ComfyUiSubmittedPrompt(val promptId: String, val queueNumber: Long?)

data class ComfyUiImageDownload(
    val mimeType: String,
    val bytes: ByteArray,
    val sha256: String
) {
    val byteSize: Long get() = bytes.size.toLong()
}

/**
 * ComfyUI API adapter. Workflow JSON is supplied explicitly by a versioned template;
 * this class never guesses graph nodes and never persists downloaded media.
 */
class ComfyUiApiClient(
    private val transport: ComfyUiApiTransport = UrlConnectionComfyUiApiTransport()
) {
    suspend fun submit(
        config: ComfyUiConnectionConfig,
        template: ComfyUiWorkflowTemplate,
        request: ImageGenerationRequest,
        clientId: String,
        promptId: String
    ): ComfyUiSubmittedPrompt {
        require(clientId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "ComfyUI client ID is invalid" }
        require(CLIENT_PROMPT_ID_PATTERN.matches(promptId)) { "ComfyUI client prompt ID must be a canonical UUID" }
        val payload = JSONObject()
            .put("prompt_id", promptId)
            .put("prompt", template.buildPrompt(request))
            .put("client_id", clientId)
        val response = jsonRequest(config, "POST", config.endpoint("/prompt"), payload)
        requireSuccess(response.statusCode)
        val body = parseObject(response.body)
        val promptId = body.optString("prompt_id").takeIf { it.matches(PROMPT_ID_PATTERN) }
            ?: throw ComfyUiApiException("ComfyUI returned an invalid prompt ID", response.statusCode)
        val queueNumber = if (body.has("number") && !body.isNull("number")) {
            body.optLong("number").takeIf { it >= 0 }
        } else null
        val nodeErrors = body.optJSONObject("node_errors")
        if (nodeErrors != null && nodeErrors.length() > 0) {
            throw ComfyUiApiException("ComfyUI rejected one or more workflow nodes", response.statusCode)
        }
        return ComfyUiSubmittedPrompt(promptId, queueNumber)
    }

    suspend fun queue(config: ComfyUiConnectionConfig): ComfyUiQueueSnapshot {
        val response = jsonRequest(config, "GET", config.endpoint("/queue"))
        requireSuccess(response.statusCode)
        val body = parseObject(response.body)
        return ComfyUiQueueSnapshot(
            runningPromptIds = parseQueueIds(body.optJSONArray("queue_running")),
            pendingPromptIds = parseQueueIds(body.optJSONArray("queue_pending"))
        )
    }

    suspend fun history(
        config: ComfyUiConnectionConfig,
        promptId: String
    ): ComfyUiHistoryResult {
        validatePromptId(promptId)
        val response = jsonRequest(config, "GET", config.endpoint("/history/$promptId"))
        requireSuccess(response.statusCode)
        val root = parseObject(response.body)
        val entry = root.optJSONObject(promptId)
            ?: if (root.has("status") || root.has("outputs")) root
            else throw ComfyUiApiException("ComfyUI history did not contain the requested job")
        val statusObject = entry.optJSONObject("status")
        val statusText = statusObject?.optString("status_str").orEmpty().lowercase()
        val completed = statusObject?.optBoolean("completed", false) == true
        val state = when {
            statusText in setOf("error", "failed") -> ImageGenerationState.FAILED
            statusText == "success" || completed -> ImageGenerationState.COMPLETED
            else -> ImageGenerationState.RUNNING
        }
        val images = mutableListOf<ComfyUiImageReference>()
        val outputs = entry.optJSONObject("outputs") ?: JSONObject()
        val outputKeys = outputs.keys()
        while (outputKeys.hasNext()) {
            val output = outputs.optJSONObject(outputKeys.next()) ?: continue
            val imageArray = output.optJSONArray("images") ?: continue
            for (index in 0 until imageArray.length()) {
                val image = imageArray.optJSONObject(index) ?: continue
                val filename = image.optString("filename").takeIf { it.isNotBlank() } ?: continue
                images += ComfyUiImageReference(
                    filename = filename,
                    subfolder = image.optString("subfolder", ""),
                    type = image.optString("type", "output")
                )
            }
        }
        return ComfyUiHistoryResult(promptId, ImageGenerationStatus(state), images.distinct())
    }

    /**
     * Checks whether ComfyUI has already recorded this prompt in history.
     * Used after queued deletion to avoid reporting a job as cancelled if it
     * completed while the queue operation was in flight.
     */
    suspend fun hasHistory(
        config: ComfyUiConnectionConfig,
        promptId: String
    ): Boolean {
        validatePromptId(promptId)
        val response = jsonRequest(config, "GET", config.endpoint("/history/$promptId"))
        if (response.statusCode == 404) return false
        requireSuccess(response.statusCode)
        val root = parseObject(response.body)
        return root.optJSONObject(promptId) != null || root.has("status") || root.has("outputs")
    }

    suspend fun status(
        config: ComfyUiConnectionConfig,
        promptId: String
    ): ImageGenerationStatus {
        validatePromptId(promptId)
        val queued = queue(config)
        return when {
            promptId in queued.runningPromptIds -> ImageGenerationStatus(ImageGenerationState.RUNNING)
            promptId in queued.pendingPromptIds -> ImageGenerationStatus(ImageGenerationState.QUEUED)
            else -> history(config, promptId).status
        }
    }

    /** Removes a queued prompt only; ComfyUI's global interrupt endpoint is intentionally not used. */
    suspend fun deleteQueued(
        config: ComfyUiConnectionConfig,
        promptId: String
    ) {
        validatePromptId(promptId)
        val payload = JSONObject().put("delete", JSONArray().put(promptId))
        val response = jsonRequest(config, "POST", config.endpoint("/queue"), payload)
        requireSuccess(response.statusCode)
    }

    /**
     * Downloads image bytes into memory only. Callers must move them into encrypted storage
     * before any persistence; this adapter has no filesystem or database access.
     */
    suspend fun download(
        config: ComfyUiConnectionConfig,
        reference: ComfyUiImageReference
    ): ComfyUiImageDownload {
        val query = listOf(
            "filename" to reference.filename,
            "subfolder" to reference.subfolder,
            "type" to reference.type
        ).joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        val response = transport.getBinary(
            url = config.endpoint("/view") + "?" + query,
            bearerToken = config.bearerToken,
            connectTimeoutMillis = config.connectTimeoutMillis,
            readTimeoutMillis = config.readTimeoutMillis,
            maxResponseBytes = MAX_IMAGE_BYTES
        )
        try {
            requireSuccess(response.statusCode)
            val mimeType = response.contentType.orEmpty().substringBefore(';').trim().lowercase()
            require(mimeType in SUPPORTED_IMAGE_MIME_TYPES) {
                "ComfyUI returned an unsupported image content type"
            }
            require(isValidImageSignature(mimeType, response.body)) {
                "ComfyUI response did not contain a valid image payload"
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(response.body)
                .joinToString("") { "%02x".format(it) }
            return ComfyUiImageDownload(mimeType, response.body, digest)
        } catch (failure: Exception) {
            // Invalid or rejected provider responses must not leave downloaded plaintext in memory.
            response.body.fill(0)
            throw failure
        }
    }

    private suspend fun jsonRequest(
        config: ComfyUiConnectionConfig,
        method: String,
        url: String,
        payload: JSONObject? = null
    ): ComfyUiApiResponse = transport.execute(
        method = method,
        url = url,
        body = payload?.toString()?.toByteArray(StandardCharsets.UTF_8),
        bearerToken = config.bearerToken,
        connectTimeoutMillis = config.connectTimeoutMillis,
        readTimeoutMillis = config.readTimeoutMillis,
        maxResponseBytes = MAX_JSON_RESPONSE_BYTES
    )

    private fun parseObject(body: String): JSONObject {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) throw ComfyUiApiException("ComfyUI returned an invalid JSON response")
        return runCatching { JSONObject(trimmed) }
            .getOrElse { throw ComfyUiApiException("ComfyUI returned an invalid JSON response") }
    }

    private fun parseQueueIds(queue: JSONArray?): Set<String> {
        if (queue == null) return emptySet()
        val result = linkedSetOf<String>()
        for (index in 0 until queue.length()) {
            val entry = queue.optJSONArray(index) ?: continue
            val id = entry.optString(1).takeIf { it.matches(PROMPT_ID_PATTERN) } ?: continue
            result += id
        }
        return result
    }

    private fun requireSuccess(statusCode: Int) {
        if (statusCode !in 200..299) {
            throw ComfyUiApiException("ComfyUI request failed (HTTP $statusCode)", statusCode)
        }
    }

    private fun validatePromptId(promptId: String) {
        require(promptId.matches(PROMPT_ID_PATTERN)) { "ComfyUI prompt ID is invalid" }
    }

    private fun isValidImageSignature(mimeType: String, bytes: ByteArray): Boolean = when (mimeType) {
        "image/png" -> bytes.size >= 8 &&
            bytes.take(8).map { it.toInt() and 0xff } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
        "image/jpeg" -> bytes.size >= 3 &&
            (bytes[0].toInt() and 0xff) == 0xff &&
            (bytes[1].toInt() and 0xff) == 0xd8 &&
            (bytes[2].toInt() and 0xff) == 0xff
        "image/webp" -> bytes.size >= 12 &&
            String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
        else -> false
    }

    companion object {
        private val PROMPT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val CLIENT_PROMPT_ID_PATTERN =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        const val MAX_JSON_RESPONSE_BYTES = 1_048_576
        const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    }
}

class ComfyUiApiException(
    message: String,
    val httpStatusCode: Int? = null
) : IOException(message)
