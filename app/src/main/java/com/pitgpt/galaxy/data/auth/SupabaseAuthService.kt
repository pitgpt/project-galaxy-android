package com.pitgpt.galaxy.data.auth

import android.content.Intent
import com.pitgpt.galaxy.data.remote.GalaxyAccessTokenProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.handleDeeplinks
import kotlinx.coroutines.flow.StateFlow

/** Single application-facing entry point for Supabase email/password authentication. */
class SupabaseAuthService(private val client: SupabaseClient) : GalaxyAccessTokenProvider {
    val sessionStatus: StateFlow<SessionStatus>
        get() = client.auth.sessionStatus

    suspend fun initialize() {
        client.auth.awaitInitialization()
    }

    fun handleDeeplinks(intent: Intent) {
        client.handleDeeplinks(intent)
    }

    suspend fun signUp(email: String, password: String) {
        require(CredentialValidation.isValid(email, password)) { "A valid email and non-empty password are required" }
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signIn(email: String, password: String) {
        require(CredentialValidation.isValid(email, password)) { "A valid email and non-empty password are required" }
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    override suspend fun accessToken(): String? {
        client.auth.awaitInitialization()
        return client.auth.currentAccessTokenOrNull()
    }
}

internal object CredentialValidation {
    fun isValid(email: String, password: String): Boolean =
        email.isNotBlank() && email.contains('@') && password.isNotEmpty()
}
