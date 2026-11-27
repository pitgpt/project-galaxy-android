package com.pitgpt.galaxy.data.providers.comfyui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUiConnectionSecurityTest {
    @Test
    fun configStringDoesNotExposeEndpointDetailsOrBearerToken() {
        val secret = "top-secret-comfy-token"
        val privatePath = "private-endpoint-path-do-not-log"
        val baseUrl = "https://comfy.example/$privatePath"
        val rendered = ComfyUiConnectionConfig(
            baseUrl = baseUrl,
            bearerToken = secret
        ).toString()

        assertFalse(rendered.contains(secret))
        assertFalse(rendered.contains(baseUrl))
        assertFalse(rendered.contains(privatePath))
        assertTrue(rendered.contains("endpointConfigured=true"))
        assertTrue(rendered.contains("bearerTokenConfigured=true"))
    }
}
