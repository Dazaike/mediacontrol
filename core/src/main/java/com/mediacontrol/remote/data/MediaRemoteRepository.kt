package com.mediacontrol.remote.data

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController as FrameworkController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mediacontrol.remote.service.MediaListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import java.util.concurrent.Executor
import androidx.media3.common.Player
private const val PREFS_NAME = "media_remote_prefs"
private const val LAST_PACKAGE_KEY = "last_package"
private val Context.sessionPrefs by preferencesDataStore(PREFS_NAME)

/** Ranking input: one live framework session with a non-null playback state. */
data class SessionCandidate(
    val packageName: String,
    val appLabel: String,
    val isPlaying: Boolean,
    val updateTimeMs: Long,
)

/**
 * Load-bearing ranking: (1) playing first, (2) newest position-update time,
 * (3) saved last-package preference, (4) alphabetical appLabel. Pure for testability.
 */
internal fun pickTopSession(
    candidates: List<SessionCandidate>,
    lastPackage: String?,
): SessionCandidate? {
    if (candidates.isEmpty()) return null
    return candidates.sortedWith(
        compareByDescending<SessionCandidate> { it.isPlaying }
            .thenByDescending { it.updateTimeMs }
            .thenBy { if (it.packageName == lastPackage) 0 else 1 }
            .thenBy { it.appLabel },
    ).first()
}

private suspend fun <T> ListenableFuture<T>.awaitCompat(executor: Executor): T =
    suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    if (!cont.isCompleted) cont.resume(get())
                } catch (e: Exception) {
                    if (!cont.isCompleted) cont.resumeWithException(e)
                }
            },
            executor,
        )
        cont.invokeOnCancellation { cancel(false) }
    }

/**
 * Discovers, ranks, and controls whichever media sessions are local to the device this
 * repository runs on. Used unmodified on both the watch (Step 1) and the phone (companion
 * relay source) — the only difference is which device's NotificationListenerService feeds it.
 */
class MediaRemoteRepository(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, MediaListenerService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(appContext) }

    private val _activeSession = MutableStateFlow<ControlledSession?>(null)
    val activeSession: StateFlow<ControlledSession?> = _activeSession.asStateFlow()

    // Raw Media3 transport for the Queue screen (null for framework-only players).
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    // Every live session with a playback state; feeds the Players screen.
    private val _liveSessions = MutableStateFlow<List<SessionCandidate>>(emptyList())
    val liveSessions: StateFlow<List<SessionCandidate>> = _liveSessions.asStateFlow()

    private var attachedPackage: String? = null
    private var media3: MediaController? = null
    private var framework: FrameworkController? = null

    private val frameworkCallback = object : FrameworkController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshSessions()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshSessions()
        }

        override fun onSessionDestroyed() {
            refreshSessions()
        }

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            refreshSessions()
        }

        override fun onQueueTitleChanged(title: CharSequence?) {
            refreshSessions()
        }
    }

    val queue: List<QueueTrack>
        get() = try {
            framework?.queue?.mapNotNull { item ->
                val title = item.description.title?.toString()
                if (title.isNullOrEmpty()) null else {
                    val artist = item.description.subtitle?.toString()
                        ?: item.description.description?.toString()
                    QueueTrack(item.queueId, title, artist)
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    val queueTitle: String?
        get() = try {
            framework?.queueTitle?.toString()
        } catch (e: Exception) {
            null
        }

    suspend fun skipToQueueItem(queueId: Long): Boolean = dispatch { _, fw ->
        fw.transportControls.skipToQueueItem(queueId)
    }

    @Volatile
    private var cachedLastPackage: String? = null

    private val refreshMutex = Mutex()
    @Volatile private var refreshQueued = false
    private var lastPublished: ControlledSession? = null
    private var lastPublishAt = 0L
    private var cachedArtBytes: ByteArray? = null
    private var cachedArtGen = -1
    private var cachedArtW = -1
    private var cachedArtH = -1

    init {
        scope.launch(Dispatchers.IO) { cachedLastPackage = readStoredPackage() }
    }

    fun refreshSessions() {
        refreshQueued = true
        scope.launch {
            if (!refreshMutex.tryLock()) return@launch
            try {
                while (true) {
                    refreshQueued = false
                    refreshInternal()
                    if (!refreshQueued) break
                    delay(200)
                }
            } finally {
                refreshMutex.unlock()
                if (refreshQueued) refreshSessions()
            }
        }
    }

    /** Optimistic: persists even when the package has no live session yet. */
    fun select(packageName: String) {
        scope.launch { attach(packageName) }
    }

    fun saveLastPackage(packageName: String) {
        cachedLastPackage = packageName
        scope.launch(Dispatchers.IO) {
            try {
                appContext.sessionPrefs.edit { it[stringPreferencesKey(LAST_PACKAGE_KEY)] = packageName }
            } catch (e: Exception) {
                // Preference persistence is best-effort; ranking falls back gracefully.
            }
        }
    }

    fun readLastPackage(): String? = cachedLastPackage

    suspend fun play(): Boolean = dispatch { c3, fw ->
        if (c3 != null) c3.play() else fw.transportControls.play()
    }.also { if (it) emitPlaying(true) }

    suspend fun pause(): Boolean = dispatch { c3, fw ->
        if (c3 != null) c3.pause() else fw.transportControls.pause()
    }.also { if (it) emitPlaying(false) }

    suspend fun togglePlayPause(): Boolean {
        val playing = withContext(Dispatchers.Main.immediate) {
            media3?.takeIf { it.isConnected }?.isPlaying
                ?: _activeSession.value?.isPlaying
                ?: return@withContext null
        } ?: return false
        return if (playing) pause() else play()
    }

    suspend fun next(): Boolean = dispatch { c3, fw ->
        if (c3 != null) c3.seekToNext() else fw.transportControls.skipToNext()
    }

    suspend fun previous(): Boolean = dispatch { c3, fw ->
        if (c3 != null) c3.seekToPrevious() else fw.transportControls.skipToPrevious()
    }

    suspend fun seekTo(positionMs: Long): Boolean = dispatch { c3, fw ->
        if (c3 != null) c3.seekTo(positionMs) else fw.transportControls.seekTo(positionMs)
    }

    private suspend fun dispatch(block: (MediaController?, FrameworkController) -> Unit): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val fw = framework ?: return@withContext false
            try {
                block(media3?.takeIf { it.isConnected }, fw)
                true
            } catch (e: Exception) {
                false
            }
        }

    private suspend fun refreshInternal() {
        val live = activeFrameworkControllers().filter {
            try {
                it.playbackState != null
            } catch (e: Exception) {
                false
            }
        }
        if (live.isEmpty()) {
            detach()
            return
        }
        val candidates = live.map { it.toCandidate() }
        _liveSessions.value = candidates
        // Non-null whenever sessions exist; pickTop only yields null for an empty list.
        val top = pickTopSession(candidates, cachedLastPackage) ?: return
        if (top.packageName == attachedPackage && framework != null) {
            publishSnapshot()
        } else {
            attach(top.packageName, live)
        }
    }

    private suspend fun attach(packageName: String, known: List<FrameworkController>? = null) {
        detach()
        attachedPackage = packageName
        saveLastPackage(packageName)
        val live = known ?: activeFrameworkControllers()
        _liveSessions.value = live.map { it.toCandidate() }
        val fw = live.firstOrNull { it.packageName == packageName }
        if (fw == null) return // Optimistic select: pref kept, session stays null.
        framework = fw
        try {
            fw.registerCallback(frameworkCallback, mainHandler)
        } catch (e: Exception) {
            // Proceed unobserved; next event-driven refresh still converges.
        }
        media3 = tryConnectMedia3(packageName)
        _player.value = media3
        publishSnapshot()
    }

    private fun detach() {
        framework?.let {
            try {
                it.unregisterCallback(frameworkCallback)
            } catch (e: Exception) {
                // Already dead; nothing to unobserve.
            }
        }
        framework = null
        media3?.let {
            try {
                it.release()
            } catch (e: Exception) {
                // Release is best-effort during teardown/switch.
            }
        }
        media3 = null
        _player.value = null
        attachedPackage = null
        _liveSessions.value = emptyList()
        lastPublished = null
        lastPublishAt = 0L
        cachedArtBytes = null
        cachedArtGen = -1
        _activeSession.value = null
    }

    private fun emitPlaying(playing: Boolean) {
        val current = _activeSession.value ?: return
        if (current.isPlaying == playing) return
        val next = current.copy(isPlaying = playing)
        lastPublished = next
        lastPublishAt = SystemClock.elapsedRealtime()
        _activeSession.value = next
    }

    private fun publishSnapshot() {
        val snap = try {
            framework?.toSnapshot()
        } catch (e: Exception) {
            null
        }
        val now = SystemClock.elapsedRealtime()
        if (!shouldPublishSession(lastPublished, snap, now - lastPublishAt)) return
        lastPublished = snap
        lastPublishAt = now
        _activeSession.value = snap
    }

    private fun activeFrameworkControllers(): List<FrameworkController> =
        try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            // Notification-listener access not granted yet; onboarding deep-links to settings.
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Primary transport path: connect a Media3 controller to the package's session
     * service and drive it via the Player interface. Null when the package hosts no
     * Media3/MediaBrowser service (framework TransportControls covers those).
     */
    private suspend fun tryConnectMedia3(packageName: String): MediaController? {
        val component = resolveSessionService(packageName) ?: return null
        return try {
            val token = SessionToken(appContext, component)
            val future = MediaController.Builder(appContext, token).buildAsync()
            withTimeoutOrNull(2500) { future.awaitCompat(mainExecutor) }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveSessionService(packageName: String): ComponentName? {
        val pm = appContext.packageManager
        val actions = listOf(
            "androidx.media3.session.MediaSessionService",
            "android.media.browse.MediaBrowserService",
        )
        for (action in actions) {
            val intent = Intent(action).setPackage(packageName)
            val infos = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
            val service = infos.firstOrNull() ?: continue
            return ComponentName(packageName, service.serviceInfo.name)
        }
        return null
    }

    private suspend fun readStoredPackage(): String? =
        try {
            appContext.sessionPrefs.data
                .map { it[stringPreferencesKey(LAST_PACKAGE_KEY)] }
                .first()
        } catch (e: Exception) {
            null
        }

    @Suppress("DEPRECATION")
    private fun appLabelOf(packageName: String): String =
        try {
            val info = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(info)?.toString() ?: packageName
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

    private fun FrameworkController.toCandidate(): SessionCandidate {
        val state = try {
            playbackState
        } catch (e: Exception) {
            null
        }
        return SessionCandidate(
            packageName = packageName,
            appLabel = appLabelOf(packageName),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            updateTimeMs = state?.lastPositionUpdateTime ?: 0L,
        )
    }

    private fun FrameworkController.toSnapshot(): ControlledSession {
        val pkg = packageName
        val md = try {
            metadata
        } catch (e: Exception) {
            null
        }
        val state = try {
            playbackState
        } catch (e: Exception) {
            null
        }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotEmpty() }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotEmpty() }
        val artKey = md?.let {
            it.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        }
        val artworkUri = try {
            artKey?.let(Uri::parse)
        } catch (e: Exception) {
            null
        }
        val rawBitmap: Bitmap? = md?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: it.description?.iconBitmap
        }
        val artworkBytes: ByteArray? = try {
            rawBitmap?.let { raw ->
                val gen = raw.generationId
                if (gen == cachedArtGen && raw.width == cachedArtW && raw.height == cachedArtH) {
                    cachedArtBytes
                } else {
                    val scaled = if (raw.width > 320 || raw.height > 320) {
                        Bitmap.createScaledBitmap(raw, 320, 320, true)
                    } else raw
                    val stream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    val bytes = stream.toByteArray()
                    cachedArtBytes = bytes
                    cachedArtGen = gen
                    cachedArtW = raw.width
                    cachedArtH = raw.height
                    bytes
                }
            }
        } catch (e: Exception) {
            null
        }
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0L
        return ControlledSession(
            packageName = pkg,
            appLabel = appLabelOf(pkg),
            title = title,
            artist = artist,
            artworkUri = artworkUri,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L,
            durationMs = duration,
            volumeIgnoredHere = true,
            artworkBytes = artworkBytes,
        )
    }
}
