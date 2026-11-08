package com.pitgpt.galaxy.data.providers.comfyui

enum class ComfyUiRoutePreference {
    AUTO,
    LAN_ONLY,
    VPN_ONLY
}

enum class ComfyUiRoute {
    LAN,
    VPN
}

/**
 * The LAN endpoint is attempted first in AUTO mode. The VPN endpoint is used only when
 * the LAN probe fails, so a connected VPN does not slow down normal home-network use.
 */
data class ComfyUiEndpointOptions(
    val lan: ComfyUiConnectionConfig? = null,
    val vpn: ComfyUiConnectionConfig? = null,
    val preference: ComfyUiRoutePreference = ComfyUiRoutePreference.AUTO
) {
    init {
        require(lan != null || vpn != null) {
            "At least one ComfyUI endpoint must be configured"
        }
        require(preference != ComfyUiRoutePreference.LAN_ONLY || lan != null) {
            "LAN-only mode requires a LAN endpoint"
        }
        require(preference != ComfyUiRoutePreference.VPN_ONLY || vpn != null) {
            "VPN-only mode requires a VPN endpoint"
        }
    }
}

data class ComfyUiRouteAttempt(
    val route: ComfyUiRoute,
    val reachable: Boolean,
    val httpStatusCode: Int?,
    val message: String
)

data class ComfyUiEndpointResolution(
    val selectedEndpoint: ComfyUiConnectionConfig?,
    val selectedRoute: ComfyUiRoute?,
    val attempts: List<ComfyUiRouteAttempt>
) {
    val reachable: Boolean
        get() = selectedEndpoint != null && selectedRoute != null
}

/**
 * Resolves the endpoint that Galaxy should use for the current network.
 *
 * This deliberately uses a real ComfyUI health probe instead of inspecting Android's
 * active-network/VPN flags: a VPN can be connected while its route to the host is unavailable.
 * The probe itself uses bounded timeouts and does not follow redirects.
 */
class ComfyUiEndpointSelector(
    private val probe: suspend (ComfyUiConnectionConfig) -> ComfyUiProbeResult = { config ->
        ComfyUiConnectionProbe()(config)
    }
) {
    suspend fun resolve(options: ComfyUiEndpointOptions): ComfyUiEndpointResolution {
        val candidates = when (options.preference) {
            ComfyUiRoutePreference.LAN_ONLY -> listOfNotNull(options.lan?.let { ComfyUiRoute.LAN to it })
            ComfyUiRoutePreference.VPN_ONLY -> listOfNotNull(options.vpn?.let { ComfyUiRoute.VPN to it })
            ComfyUiRoutePreference.AUTO -> buildList {
                options.lan?.let { add(ComfyUiRoute.LAN to it) }
                options.vpn?.let { candidate ->
                    if (options.lan?.normalizedBaseUrl != candidate.normalizedBaseUrl) {
                        add(ComfyUiRoute.VPN to candidate)
                    }
                }
            }
        }

        val attempts = mutableListOf<ComfyUiRouteAttempt>()
        for ((route, endpoint) in candidates) {
            val result = probe(endpoint)
            attempts += ComfyUiRouteAttempt(
                route = route,
                reachable = result.reachable,
                httpStatusCode = result.httpStatusCode,
                message = result.message
            )
            if (result.reachable) {
                return ComfyUiEndpointResolution(endpoint, route, attempts.toList())
            }
        }

        return ComfyUiEndpointResolution(
            selectedEndpoint = null,
            selectedRoute = null,
            attempts = attempts.toList()
        )
    }
}
