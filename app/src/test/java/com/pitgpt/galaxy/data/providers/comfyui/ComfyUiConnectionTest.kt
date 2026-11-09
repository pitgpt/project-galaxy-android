package com.pitgpt.galaxy.data.providers.comfyui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

class ComfyUiConnectionTest {
    @Test
    fun acceptsPrivateLanAndNormalizesTrailingSlash() {
        val config = ComfyUiConnectionConfig("http://192.168.1.25:8188/")
        assertEquals("http://192.168.1.25:8188", config.normalizedBaseUrl)
        assertEquals("http://192.168.1.25:8188/system_stats", config.endpoint("/system_stats"))
    }

    @Test
    fun acceptsLoopbackAndTailscaleAddressOverHttp() {
        assertEquals("http://localhost:8188", ComfyUiConnectionConfig("http://localhost:8188").normalizedBaseUrl)
        assertEquals("http://100.100.10.20:8188", ComfyUiConnectionConfig("http://100.100.10.20:8188").normalizedBaseUrl)
    }

    @Test
    fun acceptsPublicHostOnlyWhenUsingHttps() {
        assertEquals("https://comfy.example.com", ComfyUiConnectionConfig("https://comfy.example.com/").normalizedBaseUrl)
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiConnectionConfig("http://comfy.example.com:8188")
        }
    }

    @Test
    fun rejectsCredentialsQueryAndFragment() {
        listOf(
            "http://user:password@192.168.1.25:8188",
            "http://192.168.1.25:8188?token=secret",
            "http://192.168.1.25:8188#fragment"
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                ComfyUiConnectionConfig(url)
            }
        }
    }

    @Test
    fun rejectsUnsupportedSchemeAndInvalidPort() {
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiConnectionConfig("ftp://192.168.1.25:8188")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiConnectionConfig("http://192.168.1.25:70000")
        }
    }

    @Test
    fun rejectsUnsafeApiPath() {
        val config = ComfyUiConnectionConfig("http://192.168.1.25:8188")
        assertThrows(IllegalArgumentException::class.java) {
            config.endpoint("/../admin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            config.endpoint("//other-host/path")
        }
    }

    @Test
    fun rejectsTimeoutOutsideBoundedRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ComfyUiConnectionConfig("http://192.168.1.25:8188", connectTimeoutMillis = 60_000)
        }
    }

    @Test
    fun reportsHealthyComfyUiAndPassesExplicitToken() = runBlocking {
        var requestedUrl: String? = null
        var suppliedToken: String? = null
        val probe = ComfyUiConnectionProbe(ComfyUiHttpTransport { url, token, _, _, maxBytes ->
            requestedUrl = url
            suppliedToken = token
            assertEquals(1_048_576, maxBytes)
            ComfyUiHttpResponse(200, """{"system":{},"devices":[]}""")
        })

        val result = probe(ComfyUiConnectionConfig(
            baseUrl = "http://192.168.1.25:8188",
            bearerToken = "private-token"
        ))

        assertTrue(result.reachable)
        assertEquals("http://192.168.1.25:8188/system_stats", requestedUrl)
        assertEquals("private-token", suppliedToken)
        assertEquals("ComfyUI is reachable", result.message)
    }

    @Test
    fun doesNotExposeProviderErrorBodyOrToken() = runBlocking {
        val probe = ComfyUiConnectionProbe(ComfyUiHttpTransport { _, _, _, _, _ ->
            ComfyUiHttpResponse(401, """{"error":"secret-token-should-not-appear"}""")
        })

        val result = probe(ComfyUiConnectionConfig(
            baseUrl = "http://192.168.1.25:8188",
            bearerToken = "secret-token-should-not-appear"
        ))

        assertFalse(result.reachable)
        assertEquals(401, result.httpStatusCode)
        assertEquals("ComfyUI rejected the configured access token", result.message)
        assertFalse(result.message.contains("secret-token-should-not-appear"))
    }

    @Test
    fun propagatesCancellationInsteadOfTreatingEndpointAsUnavailable() = runBlocking {
        val cancellation = CancellationException("probe cancelled")
        val probe = ComfyUiConnectionProbe(ComfyUiHttpTransport { _, _, _, _, _ ->
            throw cancellation
        })

        val thrown = runCatching {
            probe(ComfyUiConnectionConfig("http://192.168.1.25:8188"))
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun rejectsSuccessfulResponseThatIsNotJson() = runBlocking {
        val probe = ComfyUiConnectionProbe(ComfyUiHttpTransport { _, _, _, _, _ ->
            ComfyUiHttpResponse(200, "<html>not ComfyUI</html>")
        })

        val result = probe(ComfyUiConnectionConfig("http://192.168.1.25:8188"))

        assertFalse(result.reachable)
        assertEquals("The endpoint responded, but did not return valid ComfyUI JSON", result.message)
    }
}
