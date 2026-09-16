package com.mediacontrol.remote.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Minimal onboarding + debug screen — this app has no other UI. Media3/notification
 * plumbing runs entirely in [PhoneApp]/[PhoneSyncService]; this activity only surfaces
 * the notification-access grant and a live "now sharing" line for confidence.
 */
class MainActivity : ComponentActivity() {

    private var btStatusText: TextView? = null

    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            btStatusText?.text = btStatusLine(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val statusText = TextView(this)
        val nowPlayingText = TextView(this)
        val enableButton = Button(this).apply {
            text = "Enable notification access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val btText = TextView(this).apply { text = btStatusLine(hasBtConnect()) }
        btStatusText = btText
        val btButton = Button(this).apply {
            text = "Grant Bluetooth access"
            setOnClickListener { requestBtConnect() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 128, 48, 48)
            addView(
                TextView(this@MainActivity).apply {
                    text = "Media Remote — Phone Companion"
                    textSize = 20f
                    setPadding(0, 0, 0, 32)
                },
            )
            addView(statusText)
            addView(enableButton)
            addView(btText)
            addView(btButton)
            addView(
                TextView(this@MainActivity).apply {
                    setPadding(0, 32, 0, 0)
                    text = "This app has no screens of its own — it relays whichever " +
                        "media app you're playing on this phone to your watch."
                },
            )
            addView(nowPlayingText)
        }
        setContentView(layout)
        if (!hasBtConnect()) requestBtConnect()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val enabled = NotificationManagerCompat
                        .getEnabledListenerPackages(this@MainActivity)
                        .contains(packageName)
                    statusText.text = if (enabled) {
                        "Notification access: ON"
                    } else {
                        "Notification access: OFF — required to see your playback"
                    }
                    delay(1_000)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as PhoneApp).repo.activeSession.collect { session ->
                    nowPlayingText.text = if (session == null) {
                        "Now sharing: nothing"
                    } else {
                        val state = if (session.isPlaying) "playing" else "paused"
                        "Now sharing: ${session.title ?: session.appLabel} ($state)"
                    }
                }
            }
        }
    }

    /** Earbud control talks RFCOMM directly, which needs runtime BLUETOOTH_CONNECT on API 31+. */
    private fun hasBtConnect(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestBtConnect() {
        if (Build.VERSION.SDK_INT >= 31) {
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun btStatusLine(granted: Boolean): String =
        if (granted) "Bluetooth access: ON"
        else "Bluetooth access: OFF — required for earbud controls"
}
