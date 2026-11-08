package com.pitgpt.galaxy.data.providers.comfyui

import java.net.URI

/**
 * Explicit user-configured ComfyUI endpoint.
 *
 * Plain HTTP is restricted to local/private network destinations. Public endpoints must use
 * HTTPS. Redirects are disabled by the transport so credentials cannot be forwarded elsewhere.
 */
data class ComfyUiConnectionConfig(
    val baseUrl: String,
    val bearerToken: String? = null,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS
) {
    val normalizedBaseUrl: String

    init {
        normalizedBaseUrl = ComfyUiEndpointPolicy.validateAndNormalize(baseUrl)
        require(connectTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "ComfyUI connection timeout is outside the allowed range"
        }
        require(readTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "ComfyUI read timeout is outside the allowed range"
        }
        require(bearerToken == null || bearerToken.length <= MAX_TOKEN_LENGTH) {
            "ComfyUI access token is too long"
        }
        require(bearerToken == null || bearerToken.isNotBlank()) {
            "ComfyUI access token must not be blank"
        }
    }

    fun endpoint(path: String): String {
        require(path.startsWith("/") && !path.startsWith("//")) {
            "ComfyUI API path must be an absolute path"
        }
        require(!path.contains("..") && !path.contains('\\') && !path.contains('?') && !path.contains('#')) {
            "ComfyUI API path contains an unsupported segment"
        }
        return normalizedBaseUrl + path
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 10_000
        const val MIN_TIMEOUT_MILLIS = 1_000
        const val MAX_TIMEOUT_MILLIS = 30_000
        const val MAX_TOKEN_LENGTH = 4_096
    }
}

object ComfyUiEndpointPolicy {
    fun validateAndNormalize(value: String): String {
        require(value.isNotBlank()) { "ComfyUI endpoint must not be blank" }
        require(value.length <= 2_048) { "ComfyUI endpoint is too long" }

        val uri = runCatching { URI(value.trim()) }
            .getOrElse { throw IllegalArgumentException("ComfyUI endpoint is not a valid URL") }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()

        require(scheme == "http" || scheme == "https") {
            "ComfyUI endpoint must use HTTP or HTTPS"
        }
        require(!host.isNullOrBlank()) { "ComfyUI endpoint must include a host" }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "ComfyUI endpoint must not contain credentials, query parameters or fragments"
        }
        require(uri.port == -1 || uri.port in 1..65_535) {
            "ComfyUI endpoint port is invalid"
        }
        require(!uri.rawPath.orEmpty().contains("..") && !uri.rawPath.orEmpty().contains('\\')) {
            "ComfyUI endpoint path contains an unsupported segment"
        }

        if (scheme == "http") {
            require(isLocalOrPrivateHost(host)) {
                "Plain HTTP is allowed only for local or private-network ComfyUI endpoints"
            }
        }

        val normalizedPath = uri.rawPath.orEmpty().trimEnd('/')
        val authority = buildString {
            append(host.let { if (it.contains(':')) "[$it]" else it })
            if (uri.port != -1) append(":").append(uri.port)
        }
        return "$scheme://$authority$normalizedPath"
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true
        }

        if (host.contains(':')) {
            // IPv6 literals only; no DNS lookup is performed by this validator.
            val normalized = host.substringBefore('%').lowercase()
            return normalized == "::1" ||
                normalized.startsWith("fc") ||
                normalized.startsWith("fd") ||
                normalized.startsWith("fe8") ||
                normalized.startsWith("fe9") ||
                normalized.startsWith("fea") ||
                normalized.startsWith("feb")
        }

        val octets = host.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || it.any { ch -> !ch.isDigit() } }) {
            return false
        }
        val ip = octets.map { it.toIntOrNull() ?: return false }
        if (ip.any { it !in 0..255 }) return false

        return ip[0] == 10 ||
            ip[0] == 127 ||
            (ip[0] == 169 && ip[1] == 254) ||
            (ip[0] == 172 && ip[1] in 16..31) ||
            (ip[0] == 192 && ip[1] == 168) ||
            (ip[0] == 100 && ip[1] in 64..127)
    }
}
