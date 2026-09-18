package com.mediacontrol.remote

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.PhoneRelaySource
import com.mediacontrol.remote.data.UmoBridgeSessionService
import com.mediacontrol.remote.tile.MediaComplicationService
import com.mediacontrol.remote.tile.MediaTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaRemoteApp : Application() {
    val phoneRelay: PhoneRelaySource by lazy { PhoneRelaySource(this) }
    val mediaSource: CombinedMediaSource by lazy { CombinedMediaSource(this, phoneRelay) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastSurfacePush = 0L
    private var lastPkg: String? = null
    private var lastTitle: String? = null
    private var lastPlaying: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        // Starts the local MediaSession bridge whenever the phone reports a live session.
        // Never stopped from here: UmoBridgeSessionService stops itself once the session
        // goes null (see UmoBridgeSessionService.applySession). Calling
        // startForegroundService on an already-running service is a harmless no-op
        // (just re-delivers onStartCommand), so no extra "already running" guard is needed.
        appScope.launch {
            mediaSource.activeSession.collect { session ->
                if (session != null) {
                    ContextCompat.startForegroundService(
                        this@MediaRemoteApp,
                        Intent(this@MediaRemoteApp, UmoBridgeSessionService::class.java),
                    )
                }
            }
        }
        // Push Tile + complication refresh on session change from the phone, throttled
        // to ≥10s. Drops (not coalesces) bursts; Tile freshness interval covers stragglers.
        // Default, not Main: this is the first touch of [mediaSource], and building the
        // relay stack (plus GMS/Tile lookups) on the main thread here costs first-frame
        // latency for work no one is waiting on.
        appScope.launch {
            mediaSource.activeSession.collect {
                if (it?.packageName == lastPkg && it?.title == lastTitle && it?.isPlaying == lastPlaying) return@collect
                val now = SystemClock.elapsedRealtime()
                if (now - lastSurfacePush < 10_000) return@collect
                lastSurfacePush = now
                lastPkg = it?.packageName
                lastTitle = it?.title
                lastPlaying = it?.isPlaying
                try {
                    TileService.getUpdater(this@MediaRemoteApp)
                        .requestUpdate(MediaTileService::class.java)
                } catch (e: Exception) {
                    // Tile not added; nothing to refresh.
                }
                try {
                    ComplicationDataSourceUpdateRequester.create(
                        this@MediaRemoteApp,
                        ComponentName(this@MediaRemoteApp, MediaComplicationService::class.java),
                    ).requestUpdateAll()
                } catch (e: Exception) {
                    // Complication not active; nothing to refresh.
                }
            }
        }
    }
}
