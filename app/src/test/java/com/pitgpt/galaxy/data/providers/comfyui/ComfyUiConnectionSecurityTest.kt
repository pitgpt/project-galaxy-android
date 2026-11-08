package com.pitgpt.galaxy.data.providers.comfyui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUiConnectionSecurityTest {
    @Test
    fun configStringDoesNotExposeBearerToken() {
        val secret = "top-secret-comfy-token"
        val rendered = ComfyUiConnectionConfig(
            baseUrl = "http://192.168.1.20:8188",
            bearerToken = secret
        ).toString()

        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("bearerTokenConfigured=true"))
    }
}
