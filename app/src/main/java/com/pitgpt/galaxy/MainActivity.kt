package com.pitgpt.galaxy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pitgpt.galaxy.ui.GalaxyAuthGate
import com.pitgpt.galaxy.ui.GalaxyHome

class MainActivity : ComponentActivity() {
    private fun handleAuthDeeplink(intent: Intent?) {
        if (intent == null) return
        (application as GalaxyApplication).auth?.handleDeeplinks(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthDeeplink(intent)
        window.statusBarColor = Color(0xFF080B18).toArgb()
        window.navigationBarColor = Color(0xFF080B18).toArgb()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = Color(0xFF080B18)
                ) {
                    val auth = (application as GalaxyApplication).auth
                    GalaxyAuthGate(auth = auth) {
                        GalaxyHome()
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
