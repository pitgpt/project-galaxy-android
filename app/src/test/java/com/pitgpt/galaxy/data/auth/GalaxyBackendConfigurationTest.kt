package com.pitgpt.galaxy.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GalaxyBackendConfigurationTest {
    private fun validConfiguration(
        apiUrl: String = "https://api.example.test/",
        supabaseUrl: String = "https://project.supabase.co/",
        key: String = "sb_publishable_example"
    ) = GalaxyBackendConfiguration(apiUrl, supabaseUrl, key)

    @Test
    fun normalizesHttpsEndpointsAndAcceptsCustomSupabaseHost() {
        val normalized = GalaxyBackendConfiguration(
            galaxyApiBaseUrl = " HTTPS://API.EXAMPLE.TEST/gateway/ ",
            supabaseUrl = "https://auth.example.org/",
            supabasePublishableKey = " sb_publishable_example "
        ).validated()

        assertEquals("https://API.EXAMPLE.TEST/gateway", normalized.galaxyApiBaseUrl)
        assertEquals("https://auth.example.org", normalized.supabaseUrl)
        assertEquals("sb_publishable_example", normalized.supabasePublishableKey)
    }

    @Test
    fun rejectsCleartextEndpoints() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(apiUrl = "http://api.example.test").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(supabaseUrl = "http://project.supabase.co").validated()
        }
    }

    @Test
    fun rejectsCredentialsQueryAndFragments() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(apiUrl = "https://user:password@api.example.test").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(apiUrl = "https://api.example.test?token=secret").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(supabaseUrl = "https://project.supabase.co/#fragment").validated()
        }
    }

    @Test
    fun rejectsPlaceholderHostsAndSupabasePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(apiUrl = "https://api.galaxy.invalid").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(supabaseUrl = "https://project.supabase.co/auth").validated()
        }
    }

    @Test
    fun rejectsEmptyOversizedAndControlCharacterPublishableKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = "  ").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = "x".repeat(4097)).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = "public-key\nwith-newline").validated()
        }
    }
}
