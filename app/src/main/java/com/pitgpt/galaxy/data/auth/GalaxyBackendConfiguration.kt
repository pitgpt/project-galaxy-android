package com.pitgpt.galaxy.data.auth

import com.pitgpt.galaxy.BuildConfig
import java.net.URI
import java.util.Base64
import org.json.JSONObject

/**
 * Public connection settings needed by the Android client to reach Galaxy Core
 * and authenticate with the configured Supabase project.
 *
 * Accepts modern publishable keys and legacy anon JWTs. Secret/service_role
 * keys and model-provider credentials must never be placed in this configuration.
 */
data class GalaxyBackendConfiguration(
    val galaxyApiBaseUrl: String,
    val supabaseUrl: String,
    val supabasePublishableKey: String
) {
    fun validated(): GalaxyBackendConfiguration {
        val apiUrl = normalizeHttpsUrl(
            galaxyApiBaseUrl,
            label = "Адрес Galaxy API",
            allowPath = true
        )
        val authUrl = normalizeHttpsUrl(
            supabaseUrl,
            label = "Адрес Supabase",
            allowPath = false
        )
        val publicKey = supabasePublishableKey.trim()
        require(publicKey.isNotEmpty()) {
            "Введи публичный Supabase publishable/anon key."
        }
        require(publicKey.length <= MAX_PUBLISHABLE_KEY_LENGTH) {
            "Supabase publishable key слишком длинный."
        }
        require(publicKey.none { it.code < 32 || it.code == 127 }) {
            "Supabase publishable key содержит недопустимые символы."
        }
        require(isAllowedPublishableKey(publicKey)) {
            "Используй Supabase publishable key (sb_publishable_...) или legacy anon key. Secret/service_role keys запрещены."
        }
        return copy(
            galaxyApiBaseUrl = apiUrl,
            supabaseUrl = authUrl,
            supabasePublishableKey = publicKey
        )
    }

    override fun toString(): String =
        "GalaxyBackendConfiguration(galaxyApiBaseUrl=$galaxyApiBaseUrl, " +
            "supabaseUrl=$supabaseUrl, supabasePublishableKey=[REDACTED])"

    companion object {
        private const val MAX_URL_LENGTH = 2048
        private const val MAX_PUBLISHABLE_KEY_LENGTH = 4096
        private const val PUBLISHABLE_KEY_PREFIX = "sb_publishable_"
        private const val SECRET_KEY_PREFIX = "sb_secret_"

        fun fromBuildConfig(): GalaxyBackendConfiguration? {
            val apiUrl = BuildConfig.GALAXY_API_BASE_URL.trim()
            val authUrl = BuildConfig.SUPABASE_URL.trim()
            val publicKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
            if (apiUrl.isBlank() || authUrl.isBlank() || publicKey.isBlank()) return null
            return runCatching {
                GalaxyBackendConfiguration(apiUrl, authUrl, publicKey).validated()
            }.getOrNull()
        }

        private fun isAllowedPublishableKey(value: String): Boolean {
            if (value.startsWith(PUBLISHABLE_KEY_PREFIX)) {
                val suffix = value.removePrefix(PUBLISHABLE_KEY_PREFIX)
                return suffix.isNotEmpty() && suffix.all { character ->
                    character in 'a'..'z' ||
                        character in 'A'..'Z' ||
                        character in '0'..'9' ||
                        character == '_' ||
                        character == '-'
                }
            }
            if (value.startsWith(SECRET_KEY_PREFIX)) return false

            // Existing Supabase projects may still use the legacy anon JWT. Inspect
            // only its public role claim; never accept service_role or arbitrary JWTs.
            return isLegacyAnonJwt(value)
        }

        private fun isLegacyAnonJwt(value: String): Boolean {
            val segments = value.split('.')
            if (segments.size != 3 || segments.any(String::isBlank)) return false
            return try {
                val decoder = Base64.getUrlDecoder()
                val headerBytes = decoder.decode(segments[0])
                val claimsBytes = decoder.decode(segments[1])
                val signatureBytes = decoder.decode(segments[2])
                try {
                    if (signatureBytes.isEmpty()) return false
                    val header = JSONObject(String(headerBytes, Charsets.UTF_8))
                    val claims = JSONObject(String(claimsBytes, Charsets.UTF_8))
                    header.optString("typ") == "JWT" &&
                        header.optString("alg") == "HS256" &&
                        claims.optString("role") == "anon"
                } finally {
                    headerBytes.fill(0)
                    claimsBytes.fill(0)
                    signatureBytes.fill(0)
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun normalizeHttpsUrl(
            value: String,
            label: String,
            allowPath: Boolean
        ): String {
            val candidate = value.trim()
            require(candidate.isNotEmpty() && candidate.length <= MAX_URL_LENGTH) {
                "$label не задан или превышает допустимую длину."
            }
            val uri = try {
                URI(candidate)
            } catch (_: Exception) {
                throw IllegalArgumentException("$label имеет неверный формат.")
            }
            val host = uri.host?.lowercase()
            require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) && !host.isNullOrBlank()) {
                "$label должен использовать HTTPS и содержать имя сервера."
            }
            require(
                uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            ) {
                "$label не должен содержать логин, пароль, query-параметры или fragment."
            }
            require(uri.port == -1 || uri.port in 1..65535) {
                "$label содержит недопустимый порт."
            }
            require(host != "invalid" && !host.endsWith(".invalid")) {
                "$label указывает на недействительный placeholder-домен."
            }
            if (!allowPath) {
                require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                    "$label должен указывать на корень Supabase project URL."
                }
            }
            return uri.normalize()
                .toASCIIString()
                .replaceFirst(Regex("(?i)^https://"), "https://")
                .trimEnd('/')
        }
    }
}
