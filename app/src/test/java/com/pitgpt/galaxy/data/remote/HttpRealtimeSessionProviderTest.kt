package com.pitgpt.galaxy.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HttpRealtimeSessionProviderTest {
    @Test fun rejectsInsecureRemoteUrl() {
        try {
            HttpRealtimeSessionProvider("http://api.example.com", GalaxyAccessTokenProvider { "token" })
            throw AssertionError("Expected insecure URL to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.orEmpty().contains("token"))
        }
    }

    @Test fun acceptsHttpsAndEmulatorUrls() {
        HttpRealtimeSessionProvider("https://api.example.com/", GalaxyAccessTokenProvider { "token" })
        HttpRealtimeSessionProvider("http://10.0.2.2:8000/", GalaxyAccessTokenProvider { "token" })
    }

    @Test fun apiExceptionContainsOnlySafeMessage() {
        val exception = GalaxyApiException(502, "Galaxy service is temporarily unavailable")
        assertEquals(502, exception.httpStatus)
        assertFalse(exception.message.orEmpty().contains("ephemeral-test-secret"))
        assertFalse(exception.message.orEmpty().contains("account-access-token"))
    }
}
