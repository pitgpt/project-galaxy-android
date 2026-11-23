package com.pitgpt.galaxy.data.auth

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun acceptsLegacySupabaseAnonJwtForExistingProjects() {
        val anonKey = legacyJwt(role = "anon")

        assertEquals(
            anonKey,
            validConfiguration(key = anonKey).validated().supabasePublishableKey
        )
    }

    @Test
    fun rejectsLegacySupabaseServiceRoleJwt() {
        val serviceRoleKey = legacyJwt(role = "service_role")

        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = serviceRoleKey).validated()
        }
    }

    @Test
    fun rejectsNewSupabaseSecretKeysAndUnknownOpaqueKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = "sb_secret_example").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = "arbitrary-provider-key").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = "sb_publishable_").validated()
        }
    }

    @Test
    fun rejectsMalformedOrNonAnonLegacyJwtKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = "eyJhbGciOiJIUzI1NiJ9.not-json.signature").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            validConfiguration(key = legacyJwt(role = "authenticated")).validated()
        }
    }

    @Test
    fun redactsPublishableKeyFromConfigurationStringRepresentation() {
        val configuration = validConfiguration()
        assertFalse(configuration.toString().contains("sb_publishable_example"))
        assertTrue(configuration.toString().contains("[REDACTED]"))
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

    private fun legacyJwt(role: String): String {
        fun encode(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))

        return listOf(
            encode("""{"alg":"HS256","typ":"JWT"}"""),
            encode("""{"iss":"supabase","role":"$role","iat":1795000000,"exp":2110000000}"""),
            encode("test-signature")
        ).joinToString(".")
    }
}
