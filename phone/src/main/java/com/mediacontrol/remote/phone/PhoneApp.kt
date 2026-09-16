package com.mediacontrol.remote.phone

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.PackageManager
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
import com.mediacontrol.remote.soundcore.SoundcoreStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

private const val TAG = "PhoneCompanion"

/** Launcher icons are rendered at this size before crossing to the watch. */
private const val ICON_PX = 64

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
    @Volatile private var lastPushKey: String? = null
    private var lastArtBytes: ByteArray? = null
    private var lastArtAsset: Asset? = null
    val soundcore: SoundcoreController by lazy { SoundcoreController(this, btMonitor) }
    @Volatile private var soundcoreStatus = SoundcoreStatus(null, "")
    @Volatile private var lastAppsKey: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PhoneApp created, refreshing sessions")
        repo.refreshSessions()
        pushAppCatalog()
        btMonitor.start()
        scope.launch {
            repo.activeSession.collect { session ->
                Log.d(TAG, "activeSession pkg=${session?.packageName} playing=${session?.isPlaying}")
                pushState(session)
            }
        }
        scope.launch {
            btMonitor.state.collect { btState ->
                Log.d(TAG, "btState device=${btState.deviceName} connected=${btState.connected}")
                pushState(repo.activeSession.value)
            }
        }
    }

    fun handleCommand(cmd: RelayCommand) {
        Log.i(TAG, "handleCommand: $cmd")
        scope.launch {
            when (cmd) {
                is RelayCommand.Play ->
                    if (repo.activeSession.value == null) repo.resumeLast() else repo.play()
                is RelayCommand.Pause -> repo.pause()
                is RelayCommand.Toggle ->
                    if (repo.activeSession.value == null) repo.resumeLast() else repo.togglePlayPause()
                is RelayCommand.Next -> repo.next()
                is RelayCommand.Previous -> repo.previous()
                is RelayCommand.Seek -> repo.seekTo(cmd.posMs)
                is RelayCommand.Select -> {
                    // Picking a player only takes control of it; playback is never
                    // started here. Unattended resume lives behind the watch's
                    // auto-start toggle, which sends Play, not Select.
                    val attached = repo.openPackage(cmd.pkg)
                    Log.i(TAG, "openPackage ${cmd.pkg} attached=$attached")
                    pushState(repo.activeSession.value)
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
                is RelayCommand.Soundcore -> {
                    soundcoreStatus = soundcore.apply(cmd.mode)
                    Log.i(TAG, "soundcore ${cmd.mode} -> mode=${soundcoreStatus.mode} err=${soundcoreStatus.error}")
                    pushState(repo.activeSession.value)
                }
                is RelayCommand.RequestApps -> {
                    // Explicit refresh must republish even when the set is unchanged.
                    lastAppsKey = null
                    pushAppCatalog()
                }
            }
        }
    }

    /**
     * Publishes every installed media-capable app so the watch can offer players that
     * hold no live session yet; [RelayProtocol.PATH_MEDIA_STATE] only ever carries
     * packages that are currently playing.
     */
    @Suppress("DEPRECATION")
    private fun pushAppCatalog() {
        scope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val pkgs = LinkedHashSet<String>()
                for (action in listOf(
                    "androidx.media3.session.MediaSessionService",
                    "android.media.browse.MediaBrowserService",
                )) {
                    pm.queryIntentServices(Intent(action), 0).forEach { pkgs += it.serviceInfo.packageName }
                }
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0,
                ).forEach { pkgs += it.activityInfo.packageName }

                val apps = pkgs
                    .filter { it != packageName && pm.getLaunchIntentForPackage(it) != null }
                    .map { it to appLabel(pm, it) }
                    .sortedBy { it.second.lowercase() }
                    .take(100)
                val key = apps.joinToString("|") { it.first }
                if (key == lastAppsKey) return@launch
                lastAppsKey = key

                val request = PutDataMapRequest.create(RelayProtocol.PATH_MEDIA_APPS).apply {
                    dataMap.putStringArrayList(RelayProtocol.KEY_APP_PKGS, ArrayList(apps.map { it.first }))
                    dataMap.putStringArrayList(RelayProtocol.KEY_APP_LABELS, ArrayList(apps.map { it.second }))
                    dataMap.putLong(RelayProtocol.KEY_APPS_REV, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@PhoneApp).putDataItem(request).await()
                Log.i(TAG, "pushed ${apps.size} media apps")

                val iconRequest = PutDataMapRequest.create(RelayProtocol.PATH_APP_ICONS).apply {
                    for ((pkg, _) in apps) {
                        iconBytes(pm, pkg)?.let { dataMap.putAsset(pkg, Asset.createFromBytes(it)) }
                    }
                    dataMap.putLong(RelayProtocol.KEY_ICONS_REV, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@PhoneApp).putDataItem(iconRequest).await()
                Log.i(TAG, "pushed app icons")
            } catch (e: Exception) {
                Log.e(TAG, "pushAppCatalog error: ${e.message}")
            }
        }
    }

    private fun appLabel(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /**
     * Launcher icon as a small PNG. Assets are transferred out of band, so they do not
     * count against the 100 KB DataItem limit — but they are still per-app payloads,
     * hence 64px.
     */
    private fun iconBytes(pm: PackageManager, pkg: String): ByteArray? = try {
        val drawable = pm.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(Canvas(bitmap))
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
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
                val artBytes = session?.artworkBytes
                val key = buildString {
                    append(session?.packageName).append('|')
                    append(session?.title).append('|')
                    append(session?.artist).append('|')
                    append(session?.isPlaying).append('|')
                    append(session?.durationMs).append('|')
                    append(currentVol).append('/').append(maxVol).append('|')
                    append(qTitle).append('|').append(queueIds.contentHashCode()).append('|')
                    append(livePkgs).append('|')
                    append(btState.deviceName).append('|').append(btState.connected).append('|')
                    append(artBytes?.size ?: 0).append('|')
                    append(soundcoreStatus.mode?.name ?: "").append('|')
                    append(soundcoreStatus.error)
                }
                if (key == lastPushKey) return@launch
                lastPushKey = key

                val putRequest = PutDataMapRequest.create(RelayProtocol.PATH_MEDIA_STATE).apply {
                    dataMap.putBoolean(RelayProtocol.KEY_HAS_SESSION, session != null)
                    dataMap.putString(RelayProtocol.KEY_PKG, session?.packageName ?: "")
                    dataMap.putString(RelayProtocol.KEY_TITLE, session?.title ?: session?.appLabel ?: "")
                    dataMap.putString(RelayProtocol.KEY_ARTIST, session?.artist ?: "")
                    dataMap.putBoolean(RelayProtocol.KEY_PLAYING, session?.isPlaying ?: false)
                    dataMap.putLong(RelayProtocol.KEY_POS, session?.positionMs ?: 0L)
                    dataMap.putLong(RelayProtocol.KEY_DUR, session?.durationMs ?: 0L)
                    dataMap.putInt(RelayProtocol.KEY_VOLUME, currentVol)
                    dataMap.putInt(RelayProtocol.KEY_MAX_VOLUME, maxVol)
                    dataMap.putStringArrayList(RelayProtocol.KEY_QUEUE, queueTitles)
                    dataMap.putStringArrayList(RelayProtocol.KEY_QUEUE_ARTISTS, queueArtists)
                    dataMap.putLongArray(RelayProtocol.KEY_QUEUE_IDS, queueIds)
                    dataMap.putString(RelayProtocol.KEY_QUEUE_TITLE, qTitle)
                    dataMap.putString(RelayProtocol.KEY_BT_DEVICE, btState.deviceName ?: "")
                    dataMap.putBoolean(RelayProtocol.KEY_BT_CONNECTED, btState.connected)
                    dataMap.putStringArrayList(RelayProtocol.KEY_LIVE_PKGS, livePkgs)
                    dataMap.putStringArrayList(RelayProtocol.KEY_LIVE_LABELS, liveLabels)
                    dataMap.putIntegerArrayList(RelayProtocol.KEY_LIVE_PLAYING, livePlaying)
                    dataMap.putString(RelayProtocol.KEY_SOUNDCORE_MODE, soundcoreStatus.mode?.name ?: "")
                    dataMap.putString(RelayProtocol.KEY_SOUNDCORE_ERROR, soundcoreStatus.error)
                    artAssetFor(artBytes)?.let { asset ->
                        dataMap.putAsset(RelayProtocol.KEY_ART_ASSET, asset)
                    }
                }.asPutDataRequest().setUrgent()

                Wearable.getDataClient(this@PhoneApp).putDataItem(putRequest).await()
            } catch (e: Exception) {
                Log.e(TAG, "pushState error: ${e.message}", e)
            }
        }
    }

    private fun artAssetFor(bytes: ByteArray?): Asset? {
        if (bytes == null) {
            lastArtBytes = null
            lastArtAsset = null
            return null
        }
        val cached = lastArtAsset
        if (cached != null && (bytes === lastArtBytes || lastArtBytes?.contentEquals(bytes) == true)) {
            return cached
        }
        val asset = Asset.createFromBytes(bytes)
        lastArtBytes = bytes
        lastArtAsset = asset
        return asset
    }
}
