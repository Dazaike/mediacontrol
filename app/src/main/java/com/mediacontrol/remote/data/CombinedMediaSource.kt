package com.mediacontrol.remote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import com.mediacontrol.remote.relay.RelayCommand
import com.mediacontrol.remote.soundcore.SoundcoreMode
import com.mediacontrol.remote.soundcore.SoundcoreStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val WATCH_PREFS = "watch_media_prefs"
private const val KEY_LAST_PKG = "last_package"
private val Context.watchPrefs by preferencesDataStore(WATCH_PREFS)

/** A player the user just asked for; drives the "Starting …" state on Now Playing. */
data class PendingLaunch(val packageName: String, val label: String, val failed: Boolean)

/**
 * Controller strictly for the phone companion app: never reads or shows media
 * playing locally on the watch. All state flows from [PhoneRelaySource] (synced from
 * the phone), and all transport/select commands fire as [RelayCommand] messages to
 * the phone.
 */
class CombinedMediaSource(
    private val appContext: Context,
    private val relay: PhoneRelaySource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Strictly from the phone companion app. Never local watch media.
    val activeSession: StateFlow<ControlledSession?> = relay.relaySession

    // Volume synced from the phone
    val volume: StateFlow<Int> = relay.volume
    val maxVolume: StateFlow<Int> = relay.maxVolume

    // Queue synced from the phone
    val queue: StateFlow<List<QueueTrack>> = relay.queue
    val queueTitle: StateFlow<String> = relay.queueTitle
    // Bluetooth audio device synced from the phone
    val btAudioState: StateFlow<BtAudioState> = relay.btAudioState
    // Album artwork JPEG bytes synced from the phone
    val artworkBytes: StateFlow<ByteArray?> = relay.artworkBytes

    // The watch does not host a local Player instance.
    val player: StateFlow<Player?> = MutableStateFlow(null).asStateFlow()

    // Every live audible session on the phone, beyond the fixed known-player presets.
    val liveSessions: StateFlow<List<SessionCandidate>> = relay.liveSessions

    // Last ambient-sound mode the phone confirmed on the earbuds, plus any failure reason.
    val soundcore: StateFlow<SoundcoreStatus> = relay.soundcore

    // Installed media apps on the phone, for the user-curated Players list.
    val phoneApps: StateFlow<List<PhoneAppInfo>> = relay.phoneApps

    @Volatile
    private var cachedLastPkg: String? = null

    private val _pendingLaunch = MutableStateFlow<PendingLaunch?>(null)
    val pendingLaunch: StateFlow<PendingLaunch?> = _pendingLaunch.asStateFlow()
    private var pendingJob: Job? = null

    init {
        scope.launch {
            cachedLastPkg = try {
                appContext.watchPrefs.data.map { it[stringPreferencesKey(KEY_LAST_PKG)] }.first()
            } catch (e: Exception) {
                null
            }
        }
    }

    fun refreshSessions() {
        // State is event-driven from the phone over DataClient.
    }

    fun readLastPackage(): String? = cachedLastPkg

    suspend fun play(): Boolean {
        relay.sendCommand(RelayCommand.Play)
        return true
    }

    suspend fun pause(): Boolean {
        relay.sendCommand(RelayCommand.Pause)
        return true
    }

    suspend fun togglePlayPause(): Boolean {
        relay.sendCommand(RelayCommand.Toggle)
        return true
    }

    suspend fun next(): Boolean {
        relay.sendCommand(RelayCommand.Next)
        return true
    }

    suspend fun previous(): Boolean {
        relay.sendCommand(RelayCommand.Previous)
        return true
    }

    suspend fun seekTo(positionMs: Long): Boolean {
        relay.sendCommand(RelayCommand.Seek(positionMs))
        return true
    }

    fun setVolume(vol: Int) {
        relay.sendCommand(RelayCommand.SetVolume(vol))
    }

    fun nudgeVolume(steps: Int) {
        if (steps > 0) {
            repeat(steps) { relay.sendCommand(RelayCommand.VolumeUp) }
        } else if (steps < 0) {
            repeat(-steps) { relay.sendCommand(RelayCommand.VolumeDown) }
        }
    }

    fun skipToQueueItem(queueId: Long) {
        relay.sendCommand(RelayCommand.SkipToQueueItem(queueId))
    }

    fun soundcoreMode(mode: SoundcoreMode) {
        relay.sendCommand(RelayCommand.Soundcore(mode))
    }

    fun requestApps() {
        relay.sendCommand(RelayCommand.RequestApps)
    }

    /** PNG bytes for a phone package's launcher icon; null until the catalog arrives. */
    suspend fun appIcon(packageName: String): ByteArray? = relay.appIcon(packageName)

    fun select(packageName: String, label: String) {
        cachedLastPkg = packageName
        scope.launch {
            try {
                appContext.watchPrefs.edit { it[stringPreferencesKey(KEY_LAST_PKG)] = packageName }
            } catch (e: Exception) {
                // Best-effort
            }
        }
        relay.sendCommand(RelayCommand.Select(packageName))
        pendingJob?.cancel()
        _pendingLaunch.value = PendingLaunch(packageName, label, failed = false)
        pendingJob = scope.launch {
            val started = withTimeoutOrNull(12_000) {
                relay.relaySession.first { it?.packageName == packageName }
            }
            if (started == null) {
                _pendingLaunch.value = PendingLaunch(packageName, label, failed = true)
                delay(5_000)
            }
            _pendingLaunch.value = null
        }
    }
}
