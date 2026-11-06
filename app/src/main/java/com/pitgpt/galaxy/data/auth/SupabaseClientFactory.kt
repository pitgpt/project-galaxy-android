package com.pitgpt.galaxy.data.auth

import android.content.Context
import com.pitgpt.galaxy.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType

/**
 * Creates the Supabase client only when public project configuration is present.
 * The publishable/anon key is public client configuration, never a service-role secret.
 */
object SupabaseClientFactory {
    const val DEEPLINK_SCHEME = "galaxy"
    const val DEEPLINK_HOST = "auth"

    fun createOrNull(context: Context): SupabaseAuthService? {
        val url = BuildConfig.SUPABASE_URL.trim()
        val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
        if (url.isBlank() || key.isBlank()) return null
        require(url.startsWith("https://") && url.endsWith(".supabase.co")) {
            "SUPABASE_URL must be the HTTPS URL of the configured Supabase project"
        }
        val client = createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
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
