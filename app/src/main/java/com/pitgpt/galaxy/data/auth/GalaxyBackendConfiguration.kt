package com.pitgpt.galaxy.data.auth

import com.pitgpt.galaxy.BuildConfig
import java.net.URI

/**
 * Public connection settings needed by the Android client to reach Galaxy Core
 * and authenticate with the configured Supabase project.
 *
 * The publishable/anon key is client-visible by design. Never put a service-role
 * key or a model-provider API key in this configuration.
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

        fun fromBuildConfig(): GalaxyBackendConfiguration? {
            val apiUrl = BuildConfig.GALAXY_API_BASE_URL.trim()
            val authUrl = BuildConfig.SUPABASE_URL.trim()
            val publicKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
            if (apiUrl.isBlank() || authUrl.isBlank() || publicKey.isBlank()) return null
            return runCatching {
                GalaxyBackendConfiguration(apiUrl, authUrl, publicKey).validated()
            }.getOrNull()
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
