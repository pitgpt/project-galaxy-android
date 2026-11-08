package com.pitgpt.galaxy.data.providers

import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRoutePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUiSavedSettingsTest {
    @Test
    fun convertsLanAndVpnSettingsToValidatedEndpoints() {
        val saved = ComfyUiSavedSettings(
            lanBaseUrl = "http://192.168.1.20:8188",
            lanBearerToken = "lan-secret",
            vpnBaseUrl = "http://100.90.0.12:8188",
            vpnBearerToken = "vpn-secret",
            preference = ComfyUiRoutePreference.VPN_ONLY
        )

        val options = saved.toEndpointOptions()

        assertEquals(ComfyUiRoutePreference.VPN_ONLY, options.preference)
        assertEquals("http://192.168.1.20:8188", options.lan?.normalizedBaseUrl)
        assertEquals("lan-secret", options.lan?.bearerToken)
        assertEquals("http://100.90.0.12:8188", options.vpn?.normalizedBaseUrl)
        assertEquals("vpn-secret", options.vpn?.bearerToken)
    }

    @Test
    fun rejectsMissingEndpoints() {
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiSavedSettings().toEndpointOptions()
        }
    }

    @Test
    fun stringRepresentationNeverContainsTokens() {
        val rendered = ComfyUiSavedSettings(
            lanBaseUrl = "http://192.168.1.20:8188",
            lanBearerToken = "top-secret",
            vpnBaseUrl = "https://comfy.example.net",
            vpnBearerToken = "another-secret"
        ).toString()

        assertFalse(rendered.contains("top-secret"))
        assertFalse(rendered.contains("another-secret"))
        assertTrue(rendered.contains("lanTokenConfigured=true"))
    }

    @Test
    fun rejectsPublicHttpEndpointDuringConversion() {
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiSavedSettings(lanBaseUrl = "http://example.com:8188").toEndpointOptions()
        }
    }

    @Test
    fun rejectsLanOnlyPreferenceWithoutLanEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiSavedSettings(
                vpnBaseUrl = "http://100.90.0.12:8188",
                preference = ComfyUiRoutePreference.LAN_ONLY
            ).toEndpointOptions()
        }
    }

    @Test
    fun preservesSavedTokenWhenNormalizedEndpointIsUnchanged() {
        val token = resolveComfyUiBearerToken(
            endpoint = "https://comfy.example.net/",
            enteredToken = "",
            clearSavedToken = false,
            previousEndpoint = "https://comfy.example.net",
            previousToken = "existing-token"
        )

        assertEquals("existing-token", token)
    }

    @Test
    fun doesNotForwardSavedTokenWhenEndpointHostChanges() {
        val token = resolveComfyUiBearerToken(
            endpoint = "https://other.example.net",
            enteredToken = "",
            clearSavedToken = false,
            previousEndpoint = "https://comfy.example.net",
            previousToken = "existing-token"
        )

        assertEquals(null, token)
    }

    @Test
    fun doesNotForwardSavedTokenWhenEndpointPortChanges() {
        val token = resolveComfyUiBearerToken(
            endpoint = "http://192.168.1.20:8189",
            enteredToken = "",
            clearSavedToken = false,
            previousEndpoint = "http://192.168.1.20:8188",
            previousToken = "existing-token"
        )

        assertEquals(null, token)
    }

    @Test
    fun explicitTokenReplacesSavedTokenForChangedEndpoint() {
        val token = resolveComfyUiBearerToken(
            endpoint = "https://other.example.net",
            enteredToken = "  replacement-token  ",
            clearSavedToken = false,
            previousEndpoint = "https://comfy.example.net",
            previousToken = "existing-token"
        )

        assertEquals("replacement-token", token)
    }

    @Test
    fun explicitRemovalOrEndpointRemovalClearsSavedToken() {
        assertEquals(
            null,
            resolveComfyUiBearerToken(
                endpoint = "https://comfy.example.net",
                enteredToken = "",
                clearSavedToken = true,
                previousEndpoint = "https://comfy.example.net",
                previousToken = "existing-token"
            )
        )
        assertEquals(
            null,
            resolveComfyUiBearerToken(
                endpoint = null,
                enteredToken = "",
                clearSavedToken = false,
                previousEndpoint = "https://comfy.example.net",
                previousToken = "existing-token"
            )
        )
    }
}
