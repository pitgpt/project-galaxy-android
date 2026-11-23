package com.pitgpt.galaxy.data.auth

import android.content.Context
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType

/**
 * Creates the Supabase client only after the public project configuration has
 * passed the same HTTPS validation used by the runtime setup screen.
 */
object SupabaseClientFactory {
    const val DEEPLINK_SCHEME = "galaxy"
    const val DEEPLINK_HOST = "auth"

    fun createOrNull(
        context: Context,
        configuration: GalaxyBackendConfiguration?
    ): SupabaseAuthService? {
        val validated = configuration?.validated() ?: return null
        val client = createSupabaseClient(
            supabaseUrl = validated.supabaseUrl,
            supabaseKey = validated.supabasePublishableKey
        ) {
            install(Auth) {
                sessionManager = AndroidKeystoreSessionManager(context)
                flowType = FlowType.PKCE
                host = DEEPLINK_HOST
                scheme = DEEPLINK_SCHEME
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
                enableLifecycleCallbacks = true
            }
        }
        return SupabaseAuthService(client)
    }
}
