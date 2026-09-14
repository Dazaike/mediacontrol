package com.mediacontrol.remote

import android.os.Bundle
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mediacontrol.remote.data.UmoBridgeSessionService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mediacontrol.remote.ui.NavGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MediaRemoteApp
        setContent {
            NavGraph(mediaSource = app.mediaSource)
        }
        lifecycleScope.launch {
            delay(1_000)
            ContextCompat.startForegroundService(
                this@MainActivity,
                Intent(this@MainActivity, UmoBridgeSessionService::class.java),
            )
        }
    }
}
