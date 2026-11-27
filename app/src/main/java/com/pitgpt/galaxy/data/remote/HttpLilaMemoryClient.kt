package com.pitgpt.galaxy.data.remote

import org.json.JSONObject
import java.net.URLEncoder

data class LilaMemorySnippet(
    val memoryId: String,
    val content: String
)

/**
 * Owner-scoped memory API client. The supplied transport owns HTTPS validation,
 * Bearer authentication, response bounds, cancellation and sanitized errors.
 */
class HttpLilaMemoryClient(
    private val requestJson: suspend (method: String, path: String, body: JSONObject?) -> JSONObject
) {
    suspend fun search(query: String = "", limit: Int = 20): List<LilaMemorySnippet> {
        require(query.length <= MAX_QUERY_CHARS)
        require(limit in 1..MAX_MEMORIES)

        val queryPart = query.trim().takeIf { it.isNotEmpty() }
            ?.let { "&query=${encode(it)}" }
            .orEmpty()
        val payload = requestJson(
            "GET",
            "/v1/lila/memories/search?limit=$limit$queryPart",
            null
        )
        val array = payload.optJSONArray("memories")
            ?: throw GalaxyApiException(null, "Сервис Lila вернул некорректный список воспоминаний.")
        if (array.length() > limit) {
            throw GalaxyApiException(null, "Список воспоминаний превышает допустимый размер.")
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw GalaxyApiException(null, "Сервис Lila вернул некорректное воспоминание.")
                val memoryId = item.optString("memory_id", "")
                val content = item.optString("content", "")
                if (memoryId.isBlank() || memoryId.length > MAX_MEMORY_ID_CHARS ||
                    content.isBlank() || content.length > MAX_MEMORY_CONTENT_CHARS
                ) {
                    throw GalaxyApiException(null, "Сервис Lila вернул некорректное воспоминание.")
                }
                add(LilaMemorySnippet(memoryId, content))
            }
        }
    }

    suspend fun save(memoryId: String, content: String): LilaMemorySnippet {
        require(memoryId.isNotBlank() && memoryId.length <= MAX_MEMORY_ID_CHARS)
        require(content.isNotBlank() && content.length <= MAX_MEMORY_CONTENT_CHARS)
        val payload = requestJson(
            "PUT",
            "/v1/lila/memories/${encode(memoryId)}",
            JSONObject().put("content", content)
        )
        if (payload.optString("memory_id", "") != memoryId || !payload.optBoolean("saved")) {
            throw GalaxyApiException(null, "Сервис Lila не подтвердил сохранение воспоминания.")
        }
        return LilaMemorySnippet(memoryId, content)
    }

    suspend fun delete(memoryId: String): Boolean {
        require(memoryId.isNotBlank() && memoryId.length <= MAX_MEMORY_ID_CHARS)
        val payload = requestJson(
            "DELETE",
            "/v1/lila/memories/${encode(memoryId)}",
            null
        )
        if (payload.optString("memory_id", "") != memoryId) {
            throw GalaxyApiException(null, "Сервис Lila вернул некорректный результат удаления.")
        }
        return payload.optBoolean("deleted")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val MAX_QUERY_CHARS = 512
        const val MAX_MEMORY_ID_CHARS = 128
        const val MAX_MEMORY_CONTENT_CHARS = 4_000
        const val MAX_MEMORIES = 20
    }
}
