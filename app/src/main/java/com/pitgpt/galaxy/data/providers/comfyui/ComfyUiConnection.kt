package com.pitgpt.galaxy.data.providers.comfyui

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Explicit user-configured ComfyUI endpoint.
 *
 * Plain HTTP is restricted to literal local/private IP addresses and the exact `localhost`
 * hostname. Other DNS hostnames require HTTPS: suffix checks such as `.local` do not pin DNS
 * answers and can be abused by DNS rebinding. Redirects are disabled by the transport.
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

    /** Avoid exposing endpoint details or bearer credentials through accidental logging. */
    override fun toString(): String =
        "ComfyUiConnectionConfig(endpointConfigured=${normalizedBaseUrl.isNotBlank()}, " +
            "bearerTokenConfigured=${bearerToken != null}, " +
            "connectTimeoutMillis=$connectTimeoutMillis, " +
            "readTimeoutMillis=$readTimeoutMillis)"

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
                "Plain HTTP requires a literal local/private IP address or exact localhost; DNS hostnames require HTTPS"
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
        // Only the reserved exact localhost name is trusted without DNS. Arbitrary
        // .localhost/.local suffixes are still DNS names and can be rebound to public hosts.
        if (host == "localhost") return true

        if (host.contains(':')) {
            // A colon makes this an IPv6-literal parse; no DNS lookup is performed. Scoped
            // literals are rejected because HTTP link-local destinations are not allowlisted.
            if ('%' in host) return false
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address
                ?: return false
            val firstOctet = address.address[0].toInt() and 0xff
            // Allow loopback and RFC 4193 unique-local addresses (fc00::/7). This includes
            // Tailscale's fd7a:115c:a1e0::/48 range. IPv6 link-local (fe80::/10) is excluded.
            return address.isLoopbackAddress || (firstOctet and 0xfe) == 0xfc
        }

        val octets = host.split('.')
        if (octets.size != 4 || octets.any { octet ->
                octet.isEmpty() ||
                    octet.length > 3 ||
                    (octet.length > 1 && octet.startsWith('0')) ||
                    octet.any { it !in '0'..'9' }
            }
        ) {
            return false
        }
        val ip = octets.map { it.toIntOrNull() ?: return false }
        if (ip.any { it !in 0..255 }) return false

        // RFC 1918, loopback, and the CGNAT range used by Tailscale. IPv4 link-local
        // (169.254/16), documentation, benchmark, multicast and reserved ranges are excluded.
        return ip[0] == 10 ||
            ip[0] == 127 ||
            (ip[0] == 172 && ip[1] in 16..31) ||
            (ip[0] == 192 && ip[1] == 168) ||
            (ip[0] == 100 && ip[1] in 64..127)
    }
}
