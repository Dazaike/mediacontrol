package com.mediacontrol.remote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.ButtonGroupScope
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.mediacontrol.remote.soundcore.SoundcoreMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Only [NowPlayingViewModel.uiState] is collected at this level. Volume, artwork and
 * pending-launch state are collected by the leaf composables that render them, so a
 * bezel detent or a volume push from the phone never invalidates the pager, the
 * backdrop or the transport row.
 */
@Composable
fun NowPlayingScreen(
    vm: NowPlayingViewModel,
    onOpenPlayers: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // Every transport action ticks: on a watch the screen is often out of view, so
    // touch is the only confirmation that a tap registered.
    val onToggle = remember(vm, haptics) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            vm.toggle()
        }
    }
    val onPrevious = remember(vm, haptics) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.previous()
        }
    }
    val onNext = remember(vm, haptics) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            vm.next()
        }
    }
    val onSeek = remember(vm) { { pos: Long -> vm.seekTo(pos) } }
    val onSoundcore = remember(vm, haptics) {
        { mode: SoundcoreMode ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            vm.setSoundcoreMode(mode)
        }
    }
    val context = LocalContext.current
    val stepPx = remember(context) {
        ViewConfiguration.get(context).scaledVerticalScrollFactor.takeIf { it > 0f } ?: 48f
    }
    val pagerState = rememberPagerState(pageCount = { 2 })

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hierarchicalFocusGroup(active = page == 0)
                    .onRotaryScrollEvent { event ->
                        if (vm.onRotary(event.verticalScrollPixels, stepPx)) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        true
                    }
                    .requestFocusOnHierarchyActive()
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                if (page == 0) {
                    when (val state = uiState) {
                        is NowPlayingUiState.Ready -> ReadyContent(
                            packageName = state.session.packageName,
                            title = state.session.title ?: state.session.appLabel,
                            artist = state.session.artist,
                            isPlaying = state.session.isPlaying,
                            positionMs = state.session.positionMs,
                            durationMs = state.session.durationMs,
                            vm = vm,
                            onToggle = onToggle,
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onSeek = onSeek,
                        )

                        else -> PendingOrIdle(
                            vm = vm,
                            loading = state is NowPlayingUiState.Loading,
                            onOpenPlayers = onOpenPlayers,
                        )
                    }
                } else {
                    MoreActionsContent(
                        vm = vm,
                        onOpenVolume = onOpenVolume,
                        onOpenQueue = onOpenQueue,
                        onOpenPlayers = onOpenPlayers,
                        onOpenSettings = onOpenSettings,
                        onSoundcore = onSoundcore,
                    )
                }
            }
        }
        // Curved clock riding the top bezel; screen-level so it survives page swipes.
        TimeText()
        // Two pages are not otherwise discoverable; the indicator fades itself out.
        HorizontalPageIndicator(pagerState = pagerState)
        VolumeOverlay(vm)
    }
}

/** Owns the volume collection so a bezel spin repaints only the arc. */
@Composable
private fun BoxScope.VolumeOverlay(vm: NowPlayingViewModel) {
    val visible by vm.volumeVisible.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val maxVolume by vm.maxVolume.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(350)),
        modifier = Modifier.align(Alignment.CenterStart),
    ) {
        LevelIndicator(value = { volume.toFloat() / maxVolume.coerceAtLeast(1).toFloat() })
    }
}

@Composable
private fun PendingOrIdle(
    vm: NowPlayingViewModel,
    loading: Boolean,
    onOpenPlayers: () -> Unit,
) {
    val pending by vm.pendingLaunch.collectAsStateWithLifecycle()
    val launching = pending
    when {
        launching != null && !launching.failed -> Text(
            text = "Connecting to ${launching.label}…",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        launching != null -> Text(
            text = "Couldn't reach ${launching.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )

        loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall)

        else -> NoSessionContent(vm = vm, onOpenPlayers = onOpenPlayers)
    }
}

/**
 * Takes only stable parameters — a `ByteArray` in the signature would make this and
 * every child unconditionally non-skippable, so artwork is collected inside
 * [AlbumBackdrop] instead.
 */
@Composable
private fun ReadyContent(
    packageName: String,
    title: String,
    artist: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    vm: NowPlayingViewModel,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    var playing by remember { mutableStateOf(isPlaying) }
    LaunchedEffect(isPlaying) { playing = isPlaying }

    Box(modifier = Modifier.fillMaxSize()) {
        AlbumBackdrop(vm)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(modifier = Modifier.height(30.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 34.dp, vertical = 4.dp),
            ) {
                AppIcon(
                    packageName = packageName,
                    load = vm::appIcon,
                    size = 20.dp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Keyed on the track so a change fades/slides rather than snapping.
                // Long titles scroll instead of ellipsising.
                AnimatedContent(
                    targetState = title to artist,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically { it / 3 })
                            .togetherWith(fadeOut(tween(160)))
                    },
                    label = "track",
                ) { (trackTitle, trackArtist) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = trackTitle,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        )
                        trackArtist?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyExtraSmall,
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .basicMarquee(iterations = Int.MAX_VALUE),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            TrackProgress(
                isPlaying = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                onSeek = onSeek,
            )

            ButtonGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onPrevious,
                    shapes = IconButtonDefaults.animatedShapes(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(TransportSideSize),
                ) {
                    Icon(MediaIcons.SkipPrevious, contentDescription = "Previous")
                }
                FilledIconButton(
                    onClick = {
                        playing = !playing
                        onToggle()
                    },
                    shapes = IconButtonDefaults.animatedShapes(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(TransportPlaySize),
                ) {
                    AnimatedContent(
                        targetState = playing,
                        transitionSpec = {
                            (fadeIn(tween(120)) + scaleIn(initialScale = 0.7f))
                                .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.7f))
                        },
                        label = "playPause",
                    ) { isPlayingNow ->
                        Icon(
                            if (isPlayingNow) MediaIcons.Pause else MediaIcons.PlayArrow,
                            contentDescription = if (isPlayingNow) "Pause" else "Play",
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = onNext,
                    shapes = IconButtonDefaults.animatedShapes(),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(TransportSideSize),
                ) {
                    Icon(MediaIcons.SkipNext, contentDescription = "Next")
                }
            }

            StatusOrBanner(vm)

            Spacer(modifier = Modifier.height(34.dp))
        }
    }
}

@Composable
private fun AlbumBackdrop(vm: NowPlayingViewModel) {
    val artworkBytes by vm.artworkBytes.collectAsStateWithLifecycle()
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
    // Crossfade rather than snap: the decode lands asynchronously, so a hard swap
    // reads as a flash on track change.
    Crossfade(targetState = bitmap, animationSpec = tween(500), label = "artwork") { art ->
        if (art != null) {
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
    }
}

@Composable
private fun MoreActionsContent(
    vm: NowPlayingViewModel,
    onOpenVolume: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPlayers: () -> Unit,
    onOpenSettings: () -> Unit,
    onSoundcore: (SoundcoreMode) -> Unit,
) {
    var sending by remember { mutableStateOf<SoundcoreMode?>(null) }
    LaunchedEffect(sending) {
        if (sending != null) {
            delay(6_000)
            sending = null
        }
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            MenuIconButton(onClick = onOpenVolume) {
                Icon(MediaIcons.VolumeUp, contentDescription = "Volume")
            }
            MenuIconButton(onClick = onOpenQueue) {
                Icon(MediaIcons.QueueMusic, contentDescription = "Queue")
            }
        }
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            MenuIconButton(onClick = onOpenPlayers) {
                Icon(MediaIcons.Apps, contentDescription = "Apps")
            }
            MenuIconButton(onClick = onOpenSettings) {
                Icon(MediaIcons.Settings, contentDescription = "Settings")
            }
        }
        ButtonGroup(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            MenuIconButton(
                onClick = {
                    sending = SoundcoreMode.NOISE_CANCELING
                    onSoundcore(SoundcoreMode.NOISE_CANCELING)
                },
            ) {
                Icon(MediaIcons.NoiseAware, contentDescription = "Noise Canceling")
            }
            MenuIconButton(
                onClick = {
                    sending = SoundcoreMode.TRANSPARENCY
                    onSoundcore(SoundcoreMode.TRANSPARENCY)
                },
            ) {
                Icon(MediaIcons.Hearing, contentDescription = "Transparency")
            }
            MenuIconButton(
                onClick = {
                    sending = SoundcoreMode.NORMAL
                    onSoundcore(SoundcoreMode.NORMAL)
                },
            ) {
                Icon(MediaIcons.NoiseControlOff, contentDescription = "Normal")
            }
        }
        SoundcoreStatusText(vm, sending)
    }
}

/** Leaf so an earbud status push never recomposes the swipe-menu button rows. */
@Composable
private fun SoundcoreStatusText(vm: NowPlayingViewModel, sending: SoundcoreMode?) {
    val status by vm.soundcore.collectAsStateWithLifecycle()
    val failed = status.error.isNotEmpty()
    val text = when {
        failed -> status.error
        sending != null && status.mode != sending -> "${sending.label} sending…"
        status.mode != null -> "${status.mode?.label} on"
        else -> ""
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyExtraSmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/** Expressive swipe-menu button: shape morph only — width animation re-measures the row. */
@Composable
private fun ButtonGroupScope.MenuIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.animatedShapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
            .weight(1f)
            .height(MenuButtonSize),
        content = { content() },
    )
}

@Composable
private fun StatusOrBanner(vm: NowPlayingViewModel) {
    val statusLine by vm.statusLine.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val volumeVisible by vm.volumeVisible.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
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
    } else if (volumeVisible) {
        Text(
            text = "Volume $volume",
            style = MaterialTheme.typography.bodyExtraSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
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
    // Position state is read inside these lambdas, never during composition: the
    // per-second tick then invalidates draw only, instead of re-laying out the screen.
    val positionOf: () -> Long = {
        val interpolated = if (isPlaying && durationMs > 0) {
            min(positionMs + (now - snapshotAt), durationMs)
        } else {
            positionMs
        }
        when {
            dragFraction != null && durationMs > 0 -> ((dragFraction ?: 0f) * durationMs).toLong()
            committedSeek != null -> committedSeek ?: interpolated
            else -> interpolated
        }
    }
    val progressFraction: () -> Float = {
        if (durationMs > 0) positionOf().coerceIn(0L, durationMs) / durationMs.toFloat() else 0f
    }
    fun fractionAt(x: Float): Float =
        if (barWidthPx <= 0f) 0f else (x / barWidthPx).coerceIn(0f, 1f)
    fun seekFraction(fraction: Float) {
        if (durationMs <= 0L) return
        val pos = (fraction * durationMs).toLong().coerceIn(0L, durationMs)
        committedSeek = pos
        onSeek(pos)
    }
    // Plain white on a dim white track: the bar stays legible over any artwork and
    // never competes with the accent-coloured controls below it.
    val trackColor = Color.White.copy(alpha = 0.26f)
    val barColor = Color.White
    Column(
        modifier = Modifier
            .fillMaxWidth(0.58f)
            .padding(top = 0.dp, bottom = 6.dp),
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
                    .drawBehind {
                        drawRect(trackColor)
                        val f = progressFraction().coerceIn(0f, 1f)
                        if (f > 0f) drawRect(barColor, size = Size(size.width * f, size.height))
                    },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ElapsedText(positionOf)
            Text(formatMs(durationMs), style = MaterialTheme.typography.bodyExtraSmall)
        }
    }
}

/** Isolates the per-second clock read so the row around it never recomposes. */
@Composable
private fun ElapsedText(positionOf: () -> Long) {
    Text(formatMs(positionOf()), style = MaterialTheme.typography.bodyExtraSmall)
}

@Composable
private fun NoSessionContent(vm: NowPlayingViewModel, onOpenPlayers: () -> Unit) {
    val autoStart by vm.autoStart.collectAsStateWithLifecycle()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Nothing playing on phone",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenPlayers) {
            Text("Open player")
        }
        SwitchButton(
            checked = autoStart,
            onCheckedChange = { vm.setAutoStart(it) },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    "Auto-start music",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}

// Slightly tighter than the Wear M3 defaults (Small 48.dp / Default 52.dp), which
// crowd the round display once the transport row and status line share the bottom.
private val TransportSideSize = 44.dp
private val TransportPlaySize = 48.dp
private val MenuButtonSize = 44.dp
