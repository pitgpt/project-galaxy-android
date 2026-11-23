package com.pitgpt.galaxy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pitgpt.galaxy.ui.GalaxyAuthGate
import com.pitgpt.galaxy.ui.GalaxyBackendSetupScreen
import com.pitgpt.galaxy.ui.GalaxyHome

class MainActivity : ComponentActivity() {
    private var pendingAuthIntent: Intent? = null

    private fun handleAuthDeeplink(intent: Intent?) {
        if (intent == null) return
        val auth = (application as GalaxyApplication).auth
        if (auth == null) {
            pendingAuthIntent = intent
        } else {
            auth.handleDeeplinks(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthDeeplink(intent)
        window.statusBarColor = Color(0xFF080B18).toArgb()
        window.navigationBarColor = Color(0xFF080B18).toArgb()

        setContent {
            val app = application as GalaxyApplication
            var backendConfigured by remember { mutableStateOf(app.hasBackendConfiguration) }
            var recoveryRequired by remember {
                mutableStateOf(app.backendConfigurationRecoveryRequired)
            }
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF080B18)
                ) {
                    if (backendConfigured) {
                        GalaxyAuthGate(auth = app.auth) {
                            GalaxyHome()
                        }
                    } else {
                        GalaxyBackendSetupScreen(
                            recoveryRequired = recoveryRequired,
                            onSave = { configuration ->
                                app.saveBackendConfiguration(configuration)
                                backendConfigured = app.hasBackendConfiguration
                                recoveryRequired = app.backendConfigurationRecoveryRequired
                                pendingAuthIntent?.let { pending ->
                                    app.auth?.handleDeeplinks(pending)
                                }
                                pendingAuthIntent = null
                            },
                            onReset = {
                                app.resetBackendConfiguration()
                                recoveryRequired = app.backendConfigurationRecoveryRequired
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthDeeplink(intent)
    }
}
