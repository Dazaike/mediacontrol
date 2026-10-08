package com.mediacontrol.remote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import com.mediacontrol.remote.data.StayOpenSettings
import com.mediacontrol.remote.relay.RelayCommand
import com.mediacontrol.remote.ui.NavGraph
import kotlinx.coroutines.launch

/**
 * With "Stay open" on, the activity registers for ambient mode. That is the Wear OS
 * mechanism for an app to stay on screen (dimmed, low power) after the wrist drops instead
 * of the system sending the user back to the watch face. The retention window is enforced
 * here: once the wrist has been down longer than the chosen time, the task moves back so
 * the normal behaviour resumes. With the setting off, no ambient support is registered
 * and nothing changes.
 */
class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var ambient by mutableStateOf(false)
    private var ambientObserver: AmbientLifecycleObserver? = null
    private var stayMs = 0L
    private var screenOffAt = 0L

    private val expire = Runnable {
        if (ambient) moveTaskToBack(true)
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambient = true
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, stayMs)
        }

        override fun onExitAmbient() {
            ambient = false
            handler.removeCallbacks(expire)
        }

        override fun onUpdateAmbient() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MediaRemoteApp
        app.phoneRelay.sendCommand(RelayCommand.Subscribe)
        lifecycleScope.launch {
            StayOpenSettings(applicationContext).minutes.collect { applyStayOpen(it) }
        }
        setContent {
            NavGraph(mediaSource = app.mediaSource, ambient = ambient)
        }
    }

    private fun applyStayOpen(minutes: Int) {
        stayMs = minutes * 60_000L
        ambientObserver?.let { lifecycle.removeObserver(it) }
        ambientObserver = null
        if (minutes <= 0) {
            handler.removeCallbacks(expire)
            ambient = false
            return
        }
        ambientObserver = AmbientLifecycleObserver(this, ambientCallback).also { lifecycle.addObserver(it) }
    }

    override fun onStop() {
        super.onStop()
        // Only a screen-off counts; leaving for another app on purpose must not be undone.
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        screenOffAt = if (stayMs > 0 && !interactive) SystemClock.elapsedRealtime() else 0L
    }

    override fun onStart() {
        super.onStart()
        val offAt = screenOffAt
        screenOffAt = 0L
        if (stayMs > 0 && offAt > 0 && SystemClock.elapsedRealtime() - offAt > stayMs) {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        super.onDestroy()
        (application as MediaRemoteApp).phoneRelay.sendCommand(RelayCommand.Unsubscribe)
    }
}
