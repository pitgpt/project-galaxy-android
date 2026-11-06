package com.pitgpt.galaxy.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthConfigurationTest {
    @Test
    fun rejectsBlankOrMalformedEmail() {
        assertFalse(CredentialValidation.isValid("", "password"))
        assertFalse(CredentialValidation.isValid("not-an-email", "password"))
    }

    @Test
    fun rejectsEmptyPassword() {
        assertFalse(CredentialValidation.isValid("denis@example.com", ""))
    }

    @Test
    fun acceptsEmailAndNonEmptyPassword() {
        assertTrue(CredentialValidation.isValid("denis@example.com", "password"))
    }
}
