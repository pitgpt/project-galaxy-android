package com.pitgpt.galaxy.data.providers.comfyui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUiEndpointSelectorTest {
    private val lan = ComfyUiConnectionConfig("http://192.168.1.20:8188")
    private val vpn = ComfyUiConnectionConfig("http://100.90.0.12:8188")

    @Test
    fun autoModeUsesLanWhenItIsReachable() = runBlocking {
        val attempted = mutableListOf<String>()
        val selector = ComfyUiEndpointSelector { config ->
            attempted += config.normalizedBaseUrl
            ComfyUiProbeResult(config == lan, 200, if (config == lan) "ok" else "offline")
        }

        val result = selector.resolve(ComfyUiEndpointOptions(lan = lan, vpn = vpn))

        assertTrue(result.reachable)
        assertEquals(ComfyUiRoute.LAN, result.selectedRoute)
        assertEquals(lan, result.selectedEndpoint)
        assertEquals(listOf(lan.normalizedBaseUrl), attempted)
        assertEquals(1, result.attempts.size)
    }

    @Test
    fun autoModeFallsBackToVpnWhenLanIsUnavailable() = runBlocking {
        val attempted = mutableListOf<ComfyUiRoute>()
        val selector = ComfyUiEndpointSelector { config ->
            val route = if (config == lan) ComfyUiRoute.LAN else ComfyUiRoute.VPN
            attempted += route
            ComfyUiProbeResult(route == ComfyUiRoute.VPN, 200, "probe")
        }

        val result = selector.resolve(ComfyUiEndpointOptions(lan = lan, vpn = vpn))

        assertTrue(result.reachable)
        assertEquals(ComfyUiRoute.VPN, result.selectedRoute)
        assertEquals(vpn, result.selectedEndpoint)
        assertEquals(listOf(ComfyUiRoute.LAN, ComfyUiRoute.VPN), attempted)
        assertEquals(2, result.attempts.size)
    }

    @Test
    fun autoModeUsesVpnWhenNoLanEndpointIsConfigured() = runBlocking {
        val selector = ComfyUiEndpointSelector {
            ComfyUiProbeResult(true, 200, "ok")
        }

        val result = selector.resolve(ComfyUiEndpointOptions(vpn = vpn))

        assertEquals(ComfyUiRoute.VPN, result.selectedRoute)
        assertEquals(vpn, result.selectedEndpoint)
    }

    @Test
    fun explicitVpnModeDoesNotProbeLan() = runBlocking {
        val attempted = mutableListOf<String>()
        val selector = ComfyUiEndpointSelector { config ->
            attempted += config.normalizedBaseUrl
            ComfyUiProbeResult(true, 200, "ok")
        }

        val result = selector.resolve(
            ComfyUiEndpointOptions(lan = lan, vpn = vpn, preference = ComfyUiRoutePreference.VPN_ONLY)
        )

        assertEquals(vpn, result.selectedEndpoint)
        assertEquals(ComfyUiRoute.VPN, result.selectedRoute)
        assertEquals(listOf(vpn.normalizedBaseUrl), attempted)
    }

    @Test
    fun returnsBothFailuresWithoutSelectingAnEndpoint() = runBlocking {
        val selector = ComfyUiEndpointSelector {
            ComfyUiProbeResult(false, 503, "unavailable")
        }

        val result = selector.resolve(ComfyUiEndpointOptions(lan = lan, vpn = vpn))

        assertFalse(result.reachable)
        assertNull(result.selectedEndpoint)
        assertNull(result.selectedRoute)
        assertEquals(2, result.attempts.size)
        assertTrue(result.attempts.all { !it.reachable })
    }

    @Test
    fun rejectsMissingEndpointConfiguration() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ComfyUiEndpointOptions()
        }
    }
}
