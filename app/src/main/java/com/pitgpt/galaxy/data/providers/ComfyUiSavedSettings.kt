package com.pitgpt.galaxy.data.providers

import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiConnectionConfig
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiEndpointOptions
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRoutePreference

/**
 * Device-local ComfyUI connection preferences. Tokens are serialized only inside the encrypted
 * provider vault; this value must never be logged or rendered directly.
 */
data class ComfyUiSavedSettings(
    val lanBaseUrl: String? = null,
    val lanBearerToken: String? = null,
    val vpnBaseUrl: String? = null,
    val vpnBearerToken: String? = null,
    val preference: ComfyUiRoutePreference = ComfyUiRoutePreference.AUTO
) {
    fun toEndpointOptions(): ComfyUiEndpointOptions {
        val lan = lanBaseUrl?.takeIf { it.isNotBlank() }?.let {
            ComfyUiConnectionConfig(it, lanBearerToken)
        }
        val vpn = vpnBaseUrl?.takeIf { it.isNotBlank() }?.let {
            ComfyUiConnectionConfig(it, vpnBearerToken)
        }
        return ComfyUiEndpointOptions(lan = lan, vpn = vpn, preference = preference)
    }

    override fun toString(): String =
        "ComfyUiSavedSettings(lanConfigured=${lanBaseUrl != null}, " +
            "lanTokenConfigured=${lanBearerToken != null}, " +
            "vpnConfigured=${vpnBaseUrl != null}, " +
            "vpnTokenConfigured=${vpnBearerToken != null}, preference=$preference)"
}

/**
 * Reuses a saved bearer token only when the endpoint origin is unchanged. A token must never be
 * silently forwarded to a different host or port after the user edits an endpoint.
 *
 * A newly entered token takes precedence over the clear-saved-token flag so replacing a credential
 * in the same edit operation cannot accidentally persist a null token.
 */
internal fun resolveComfyUiBearerToken(
    endpoint: String?,
    enteredToken: String?,
    clearSavedToken: Boolean,
    previousEndpoint: String?,
    previousToken: String?
): String? {
    if (endpoint.isNullOrBlank()) return null
    enteredToken?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (clearSavedToken) return null

    val nextOrigin = runCatching { ComfyUiConnectionConfig(endpoint).normalizedBaseUrl }.getOrNull()
    val previousOrigin = previousEndpoint?.let {
        runCatching { ComfyUiConnectionConfig(it).normalizedBaseUrl }.getOrNull()
    }
    return previousToken.takeIf { nextOrigin != null && nextOrigin == previousOrigin }
}
