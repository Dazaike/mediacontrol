package com.mediacontrol.remote.phone

import android.app.Application
import android.media.AudioManager
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.mediacontrol.remote.data.BluetoothAudioMonitor
import com.mediacontrol.remote.data.ControlledSession
import com.mediacontrol.remote.data.MediaRemoteRepository
import com.mediacontrol.remote.data.RepoHost
import com.mediacontrol.remote.relay.RelayCommand
import com.mediacontrol.remote.relay.RelayProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "PhoneCompanion"

/**
 * Headless relay: discovers/ranks/controls the phone's own local media sessions (via
 * the shared [MediaRemoteRepository]) and pushes state to the watch over the Wearable
 * Data Layer.
 */
class PhoneApp : Application(), RepoHost {

    override val repo: MediaRemoteRepository by lazy { MediaRemoteRepository(this) }
    val audio: AudioManager by lazy { getSystemService(AudioManager::class.java) }
    val btMonitor: BluetoothAudioMonitor by lazy { BluetoothAudioMonitor(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PhoneApp created, refreshing sessions")
        repo.refreshSessions()
        btMonitor.start()
        scope.launch {
            repo.activeSession.collect { session ->
                Log.i(TAG, "Observed activeSession change: pkg=${session?.packageName}, title=${session?.title}, playing=${session?.isPlaying}")
                pushState(session)
            }
        }
        scope.launch {
            btMonitor.state.collect { btState ->
                Log.i(TAG, "Observed btState change: device=${btState.deviceName}, connected=${btState.connected}")
                pushState(repo.activeSession.value)
            }
        }
    }

    fun handleCommand(cmd: RelayCommand) {
        Log.i(TAG, "handleCommand: $cmd")
        scope.launch {
            when (cmd) {
                is RelayCommand.Play -> repo.play()
                is RelayCommand.Pause -> repo.pause()
                is RelayCommand.Toggle -> repo.togglePlayPause()
                is RelayCommand.Next -> repo.next()
                is RelayCommand.Previous -> repo.previous()
                is RelayCommand.Seek -> repo.seekTo(cmd.posMs)
                is RelayCommand.Select -> {
                    try {
                        packageManager.getLaunchIntentForPackage(cmd.pkg)?.let {
                            startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to launch package ${cmd.pkg}: ${e.message}")
                    }
                    repo.select(cmd.pkg)
                }
                is RelayCommand.VolumeUp -> {
                    try {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed VolumeUp: ${e.message}")
                    }
                    pushState(repo.activeSession.value)
                }
                is RelayCommand.VolumeDown -> {
                    try {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed VolumeDown: ${e.message}")
                    }
                    pushState(repo.activeSession.value)
                }
                is RelayCommand.SetVolume -> {
                    try {
                        val clamped = cmd.volume.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, AudioManager.FLAG_SHOW_UI)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed SetVolume: ${e.message}")
                    }
                    pushState(repo.activeSession.value)
                }
                is RelayCommand.SkipToQueueItem -> {
                    repo.skipToQueueItem(cmd.queueId)
                }
            }
        }
    }

    fun pushState(session: ControlledSession?) {
        scope.launch(Dispatchers.IO) {
            try {
                val currentVol = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { 0 }
                val maxVol = try { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (e: Exception) { 15 }
                val queueList = repo.queue
                val queueTitles = ArrayList(queueList.map { it.title }.take(50))
                val queueArtists = ArrayList(queueList.map { it.artist ?: "" }.take(50))
                val queueIds = queueList.map { it.queueId }.take(50).toLongArray()
                val qTitle = repo.queueTitle ?: "Queue"

                val liveList = repo.liveSessions.value.take(30)
                val livePkgs = ArrayList(liveList.map { it.packageName })
                val liveLabels = ArrayList(liveList.map { it.appLabel })
                val livePlaying = ArrayList(liveList.map { if (it.isPlaying) 1 else 0 })

                val btState = btMonitor.state.value

                val putRequest = PutDataMapRequest.create(RelayProtocol.PATH_MEDIA_STATE).apply {
                    dataMap.putBoolean(RelayProtocol.KEY_HAS_SESSION, session != null)
                    dataMap.putString(RelayProtocol.KEY_PKG, session?.packageName ?: "")
                    dataMap.putString(RelayProtocol.KEY_TITLE, session?.title ?: session?.appLabel ?: "")
                    dataMap.putString(RelayProtocol.KEY_ARTIST, session?.artist ?: "")
                    dataMap.putBoolean(RelayProtocol.KEY_PLAYING, session?.isPlaying ?: false)
                    dataMap.putLong(RelayProtocol.KEY_POS, session?.positionMs ?: 0L)
                    dataMap.putLong(RelayProtocol.KEY_DUR, session?.durationMs ?: 0L)

                    // Volume:
                    dataMap.putInt(RelayProtocol.KEY_VOLUME, currentVol)
                    dataMap.putInt(RelayProtocol.KEY_MAX_VOLUME, maxVol)

                    // Queue:
                    dataMap.putStringArrayList(RelayProtocol.KEY_QUEUE, queueTitles)
                    dataMap.putStringArrayList(RelayProtocol.KEY_QUEUE_ARTISTS, queueArtists)
                    dataMap.putLongArray(RelayProtocol.KEY_QUEUE_IDS, queueIds)
                    dataMap.putString(RelayProtocol.KEY_QUEUE_TITLE, qTitle)

                    // Bluetooth audio device:
                    dataMap.putString(RelayProtocol.KEY_BT_DEVICE, btState.deviceName ?: "")
                    dataMap.putBoolean(RelayProtocol.KEY_BT_CONNECTED, btState.connected)
                    // Every live audible session on the phone (Players screen "other apps"):
                    dataMap.putStringArrayList(RelayProtocol.KEY_LIVE_PKGS, livePkgs)
                    dataMap.putStringArrayList(RelayProtocol.KEY_LIVE_LABELS, liveLabels)
                    dataMap.putIntegerArrayList(RelayProtocol.KEY_LIVE_PLAYING, livePlaying)
                    // Album art Asset:
                    session?.artworkBytes?.let { bytes ->
                        dataMap.putAsset(RelayProtocol.KEY_ART_ASSET, Asset.createFromBytes(bytes))
                    }

                    // Changing timestamp forces Play Services to broadcast onDataChanged every time:
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()

                val result = Wearable.getDataClient(this@PhoneApp).putDataItem(putRequest).await()
                Log.i(TAG, "pushState success: uri=${result.uri}, vol=$currentVol/$maxVol, queueSize=${queueTitles.size}, bt=${btState.deviceName}")
            } catch (e: Exception) {
                Log.e(TAG, "pushState error: ${e.message}", e)
            }
        }
    }
}
