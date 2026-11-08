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
