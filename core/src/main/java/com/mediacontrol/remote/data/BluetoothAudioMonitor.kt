package com.mediacontrol.remote.data

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BtAudioState(
    val deviceName: String?,
    val connected: Boolean,
    val profile: String?,
)

/** Exact one-line status rendered on Now Playing: the glyph carries the "connected" part. */
fun btStatusLine(state: BtAudioState): String =
    if (state.connected) "🎧 ${state.deviceName ?: "Unknown device"}"
    else ""

/**
 * Banner transition, pure for testability. Fires only on a connected→disconnected
 * edge while playing; any reconnect clears.
 */
fun nextBanner(
    prevConnected: Boolean,
    connected: Boolean,
    isPlaying: Boolean,
    current: Pair<String, String>?,
): Pair<String, String>? =
    when {
        connected -> null
        prevConnected && isPlaying -> "Bluetooth disconnected" to "Playback paused"
        else -> current
    }

class BluetoothAudioMonitor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BtAudioState(null, false, null))
    val state: StateFlow<BtAudioState> = _state.asStateFlow()

    private val btManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)
    private var a2dpProxy: BluetoothProfile? = null
    private var leProxy: BluetoothProfile? = null

    private val audioManager: AudioManager? =
        appContext.getSystemService(AudioManager::class.java)

    /**
     * Modern device-routing signal: fires immediately on BT audio connect/disconnect
     * regardless of whether the app is foregrounded, unlike the profile broadcast below
     * which can lag or be missed while backgrounded. Used purely as a "requery now"
     * trigger; [query] still resolves device name/profile via the proxies.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    private var started = false

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy
            else if (profile == LE_AUDIO_PROFILE) leProxy = proxy
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dpProxy = null
            else if (profile == LE_AUDIO_PROFILE) leProxy = null
            refresh()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    @Synchronized
    fun start() {
        if (!started) {
            started = true
            val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) filter.addAction(LE_AUDIO_CONNECTION_ACTION)
            try {
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            } catch (e: Exception) {
                started = false
                return
            }
        }
        try {
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: Exception) {
            // Callback registration is best-effort; broadcasts/proxies still cover us.
        }
        bindProxies()
        refresh()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Already unregistered; nothing to release.
        }
        try {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
            // Already unregistered; nothing to release.
        }
        closeProxies()
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val next = try {
                query()
            } catch (e: Exception) {
                BtAudioState(null, false, null)
            }
            _state.value = next
        }
    }

    private fun bindProxies() {
        val adapter = btManager?.adapter ?: return
        if (!hasBtConnect()) return
        try {
            adapter.getProfileProxy(appContext, proxyListener, BluetoothProfile.A2DP)
        } catch (e: Exception) {
            // Proxy binding is best-effort; broadcasts still trigger requeries.
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                adapter.getProfileProxy(appContext, proxyListener, LE_AUDIO_PROFILE)
            } catch (e: Exception) {
                // LE Audio stays unavailable; A2DP is the required path.
            }
        }
    }

    private fun closeProxies() {
        val adapter = btManager?.adapter
        try {
            a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            if (Build.VERSION.SDK_INT >= 33) {
                leProxy?.let { adapter?.closeProfileProxy(LE_AUDIO_PROFILE, it) }
            }
        } catch (e: Exception) {
            // Release is best-effort during teardown.
        }
        a2dpProxy = null
        leProxy = null
    }

    private fun query(): BtAudioState {
        if (!hasBtConnect()) return BtAudioState(null, false, null)
        leProxy?.connectedDevices.orEmpty().firstOrNull()?.let {
            return BtAudioState(it.displayName(), true, "LE Audio")
        }
        a2dpProxy?.connectedDevices.orEmpty().firstOrNull()?.let {
            return BtAudioState(it.displayName(), true, "A2DP")
        }
        return BtAudioState(null, false, null)
    }

    /** Currently connected BT audio devices; empty when the proxies are unbound or permission is missing. */
    fun connectedDevices(): List<BluetoothDevice> {
        if (!hasBtConnect()) return emptyList()
        return (a2dpProxy?.connectedDevices.orEmpty() + leProxy?.connectedDevices.orEmpty())
            .distinctBy { it.address }
    }

    private fun BluetoothDevice.displayName(): String =
        try {
            name ?: "Unknown device"
        } catch (e: Exception) {
            "Unknown device"
        }

    private fun hasBtConnect(): Boolean {
        // BLUETOOTH_CONNECT only exists on API 31+; below that, classic BT was install-time.
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val LE_AUDIO_PROFILE = 22
        private const val LE_AUDIO_CONNECTION_ACTION =
            "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED"
    }
}
