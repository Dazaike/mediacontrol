package com.mediacontrol.remote

import android.app.Application
import android.content.ComponentName
import android.os.SystemClock
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.PhoneRelaySource
import com.mediacontrol.remote.tile.MediaComplicationService
import com.mediacontrol.remote.tile.MediaTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MediaRemoteApp : Application() {
    val phoneRelay: PhoneRelaySource by lazy { PhoneRelaySource(this) }
    val mediaSource: CombinedMediaSource by lazy { CombinedMediaSource(this, phoneRelay) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastSurfacePush = 0L

    override fun onCreate() {
        super.onCreate()
        // Push Tile + complication refresh on session change from the phone,
        // throttled to ≥2s. Drops (not coalesces) bursts; Tile freshness interval covers stragglers.
        appScope.launch {
            mediaSource.activeSession.collect {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSurfacePush < 2_000) return@collect
                lastSurfacePush = now
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
