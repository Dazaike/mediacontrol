package com.mediacontrol.remote.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.StateBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.mediacontrol.remote.MainActivity
import com.mediacontrol.remote.MediaRemoteApp
import com.mediacontrol.remote.data.ControlledSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val ID_OPEN = "open"
private const val ID_TOGGLE = "toggle"
private const val ID_NEXT = "next"

/**
 * Current song + Play/Pause + Next only (no volume/queue: tile update budget).
 * Tap body opens Now Playing; updates arrive via onTileRequest plus app-pushed
 * refresh on playback change (throttled ≥2s in MediaRemoteApp).
 *
 * Layout uses ProtoLayout builders: tiles 1.4 shells (TileService, Tile.Builder)
 * consume ProtoLayout Timeline/State.
 */
class MediaTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun mediaSource() = (application as MediaRemoteApp).mediaSource

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val state = requestParams.currentState
        when (state.lastClickableId) {
            ID_TOGGLE -> scope.launch { mediaSource().togglePlayPause() }
            ID_NEXT -> scope.launch { mediaSource().next() }
        }
        return Futures.immediateFuture(buildTile(mediaSource().activeSession.value, state))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildTile(
        session: ControlledSession?,
        state: StateBuilders.State,
    ): TileBuilders.Tile {
        val title = session?.title ?: session?.appLabel ?: "Nothing playing"
        val glyph = if (session?.isPlaying == true) "⏸" else "▶"

        val openApp = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build(),
            )
            .build()

        fun loadClickable(id: String) = ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(
                ActionBuilders.LoadAction.Builder()
                    .setRequestState(state)
                    .build(),
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(title)
                    .setMaxLines(2)
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setClickable(
                                ModifiersBuilders.Clickable.Builder()
                                    .setId(ID_OPEN)
                                    .setOnClick(openApp)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(glyphBox(glyph, loadClickable(ID_TOGGLE)))
                    .addContent(glyphBox("⏭", loadClickable(ID_NEXT)))
                    .build(),
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(column))
            .setFreshnessIntervalMillis(60_000)
            .build()
    }

    private fun glyphBox(
        glyph: String,
        clickable: ModifiersBuilders.Clickable,
    ) = LayoutElementBuilders.Box.Builder()
        .addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(glyph)
                .setMaxLines(1)
                .build(),
        )
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setClickable(clickable)
                .build(),
        )
        .build()
}
