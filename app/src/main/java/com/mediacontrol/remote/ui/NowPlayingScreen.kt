package com.mediacontrol.remote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.mediacontrol.remote.data.ControlledSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

@Composable
fun NowPlayingScreen(
    vm: NowPlayingViewModel,
    onOpenPlayers: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val artworkBytes by vm.artworkBytes.collectAsStateWithLifecycle()
    val onToggle = remember(vm) { { vm.toggle() } }
    val onPrevious = remember(vm) { { vm.previous() } }
    val onNext = remember(vm) { { vm.next() } }
    val onSeek = remember(vm) { { pos: Long -> vm.seekTo(pos) } }
    val pagerState = rememberPagerState(pageCount = { 2 })

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (page == 0) {
                    when (val state = uiState) {
                        NowPlayingUiState.Loading -> {
                            Text("Loading…", style = MaterialTheme.typography.bodySmall)
                        }

                        NowPlayingUiState.NoSession -> {
                            NoSessionContent(onOpenPlayers = onOpenPlayers)
                        }

                        is NowPlayingUiState.Ready -> {
                            ReadyContent(
                                session = state.session,
                                artworkBytes = artworkBytes,
                                vm = vm,
                                onToggle = onToggle,
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onSeek = onSeek,
                            )
                        }
                    }
                } else {
                    MoreActionsContent(
                        onOpenVolume = onOpenVolume,
                        onOpenQueue = onOpenQueue,
                        onOpenPlayers = onOpenPlayers,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    session: ControlledSession,
    artworkBytes: ByteArray?,
    vm: NowPlayingViewModel,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    var playing by remember { mutableStateOf(session.isPlaying) }
    LaunchedEffect(session.isPlaying) { playing = session.isPlaying }

    Box(modifier = Modifier.fillMaxSize()) {
        AlbumBackdrop(artworkBytes)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp),
            ) {
                Text(
                    text = session.title ?: session.appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session.artist?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

        TrackProgress(
            isPlaying = playing,
            positionMs = session.positionMs,
            durationMs = session.durationMs,
            onSeek = onSeek,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onPrevious,
                modifier = Modifier.size(IconButtonDefaults.SmallButtonSize),
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            FilledIconButton(
                onClick = {
                    playing = !playing
                    onToggle()
                },
                modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
            FilledTonalIconButton(
                onClick = onNext,
                modifier = Modifier.size(IconButtonDefaults.SmallButtonSize),
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }

        StatusOrBanner(vm)

        Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun AlbumBackdrop(artworkBytes: ByteArray?) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artworkBytes) {
        bitmap = withContext(Dispatchers.Default) {
            artworkBytes?.let { bytes ->
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                    while (maxDim / sample > 96) sample *= 2
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = false
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    val art = bitmap ?: return
    Image(
        bitmap = art,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
    )
}

@Composable
private fun MoreActionsContent(
    onOpenVolume: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPlayers: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            FilledTonalIconButton(
                onClick = onOpenVolume,
                modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume")
            }
            FilledTonalIconButton(
                onClick = onOpenQueue,
                modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            FilledTonalIconButton(
                onClick = onOpenPlayers,
                modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
            ) {
                Icon(Icons.Filled.Apps, contentDescription = "Apps")
            }
            FilledTonalIconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    }
}

@Composable
private fun StatusOrBanner(vm: NowPlayingViewModel) {
    val statusLine by vm.statusLine.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val currentBanner = banner
    if (currentBanner != null) {
        Text(
            text = "${currentBanner.first} — ${currentBanner.second}",
            style = MaterialTheme.typography.bodyExtraSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clickable { vm.dismissBanner() },
        )
    } else if (statusLine.isNotEmpty()) {
        Text(
            text = statusLine,
            style = MaterialTheme.typography.bodyExtraSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun TrackProgress(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val snapshotAt = remember(positionMs, isPlaying) { android.os.SystemClock.elapsedRealtime() }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var committedSeek by remember { mutableStateOf<Long?>(null) }
    var barWidthPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                delay(1_000)
                now = android.os.SystemClock.elapsedRealtime()
            }
        }
    }
    LaunchedEffect(positionMs) {
        val seek = committedSeek
        if (seek != null && abs(positionMs - seek) < 3_000L) committedSeek = null
    }
    val interpolated = if (isPlaying && durationMs > 0) {
        min(positionMs + (now - snapshotAt), durationMs)
    } else {
        positionMs
    }
    val position = when {
        dragFraction != null && durationMs > 0 -> (dragFraction!! * durationMs).toLong()
        committedSeek != null -> committedSeek!!
        else -> interpolated
    }
    val progress = if (durationMs > 0) (position.coerceIn(0L, durationMs) / durationMs.toFloat()) else 0f
    fun fractionAt(x: Float): Float =
        if (barWidthPx <= 0f) 0f else (x / barWidthPx).coerceIn(0f, 1f)
    fun seekFraction(fraction: Float) {
        if (durationMs <= 0L) return
        val pos = (fraction * durationMs).toLong().coerceIn(0L, durationMs)
        committedSeek = pos
        onSeek(pos)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth(0.58f)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onSizeChanged { barWidthPx = it.width.toFloat() }
                .pointerInput(durationMs) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: return@withTimeoutOrNull true
                                if (!change.pressed) return@withTimeoutOrNull true
                                if ((change.position - down.position).getDistance() > slop) {
                                    return@withTimeoutOrNull true
                                }
                            }
                        }
                        if (cancelled != null) return@awaitEachGesture
                        dragFraction = fractionAt(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                val f = dragFraction
                                dragFraction = null
                                if (f != null) seekFraction(f)
                                break
                            }
                            dragFraction = fractionAt(change.position.x)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMs(position), style = MaterialTheme.typography.bodyExtraSmall)
            Text(formatMs(durationMs), style = MaterialTheme.typography.bodyExtraSmall)
        }
    }
}

@Composable
private fun NoSessionContent(onOpenPlayers: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Nothing playing on phone",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenPlayers) {
            Text("Open player")
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}
