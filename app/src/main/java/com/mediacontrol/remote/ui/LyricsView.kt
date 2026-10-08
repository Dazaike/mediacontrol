package com.mediacontrol.remote.ui

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Text
import com.mediacontrol.remote.data.LyricsUiState
import com.mediacontrol.remote.lyrics.LyricLine
import com.mediacontrol.remote.lyrics.Lyrics
import com.mediacontrol.remote.lyrics.LyricsSync
import com.mediacontrol.remote.lyrics.wordFade
import com.mediacontrol.remote.lyrics.currentLineIndex
import kotlin.math.abs

/** Steps in a word's fade from dim to lit; each step is one recomposition of the line. */
private const val FADE_STEPS = 8

private const val MIN_SHRINK = 0.7f
private val SlotHeight = 56.dp
private val DimColor = Color(0xFF9E9E9E).copy(alpha = 0.4f)
private val PlainColor = Color(0xFFE0E0E0)
private val EmptyColor = Color(0xFF9E9E9E)
private const val POSITION_REFRESH_MS = 33L

/**
 * Lyrics pager page. Nothing is fetched and no position beacons flow until the page is
 * the visible one; swiping away stops both.
 */
@Composable
fun LyricsPage(
    vm: LyricsViewModel,
    isActive: Boolean,
    onOpenSource: () -> Unit,
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val trackKey by vm.currentTrackKey.collectAsStateWithLifecycle()

    // Live only while the panel is the visible one AND the app is on screen: with the
    // screen off (or ambient) nothing is fetched, no position beacons flow, no frames run.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val live = isActive && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    LaunchedEffect(live) { vm.onPageActive(live) }
    DisposableEffect(vm) { onDispose { vm.onPageActive(false) } }
    LaunchedEffect(live, trackKey, provider) {
        val p = provider
        if (live && p != null && trackKey.isNotBlank()) vm.ensureLyrics(p)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenSource,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!live) return@Box
        when (val state = uiState) {
            is LyricsUiState.Ready -> LyricsView(
                lyrics = state.lyrics,
                positionProvider = { vm.positionMs(SystemClock.elapsedRealtime()) },
            )
            is LyricsUiState.Loading -> EmptyLyrics("Loading lyrics…")
            is LyricsUiState.None, LyricsUiState.Idle -> EmptyLyrics("No lyrics available")
        }
    }
}

@Composable
private fun EmptyLyrics(text: String) {
    Text(
        text = text,
        color = EmptyColor,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
fun LyricsView(lyrics: Lyrics, positionProvider: () -> Long) {
    if (lyrics.sync == LyricsSync.PLAIN) {
        PlainLyrics(lyrics)
    } else {
        SyncedLyrics(lyrics, positionProvider)
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    val state = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = state,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(lyrics.lines) { line ->
            Text(
                text = line.text,
                color = PlainColor,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item { ProviderLabel(lyrics) }
    }
}

@Composable
private fun ProviderLabel(lyrics: Lyrics) {
    Text(
        text = lyrics.provider.label,
        color = Color.White.copy(alpha = 0.35f),
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Samples [positionProvider] at about 30 Hz, but only while composed (i.e. page visible). */
@Composable
private fun rememberPlaybackPosition(positionProvider: () -> Long): State<Long> {
    val position = remember { mutableLongStateOf(positionProvider()) }
    LaunchedEffect(positionProvider) {
        var lastSample = 0L
        while (true) {
            val nowMs = withFrameNanos { it / 1_000_000L }
            if (nowMs - lastSample >= POSITION_REFRESH_MS) {
                lastSample = nowMs
                position.longValue = positionProvider()
            }
        }
    }
    return position
}

/**
 * Three fixed slots (previous, current, next). The slot column is shifted by the lag of
 * an animated index behind the real one, so a line change slides the block upward; each
 * line's size and brightness follow its distance from that animated index.
 */
@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionProvider: () -> Long) {
    val lines = lyrics.lines
    val position = rememberPlaybackPosition(positionProvider)
    val index by remember(lines) { derivedStateOf { currentLineIndex(lines, position.value) } }
    val shown by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "lyricsIndex",
    )
    val slotPx = with(LocalDensity.current) { SlotHeight.toPx() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                translationY = (index - shown).coerceIn(-1f, 1f) * slotPx
            },
        ) {
            for (slot in 0..2) {
                val lineIndex = index - 1 + slot
                LyricSlot(
                    line = lines.getOrNull(lineIndex),
                    sync = lyrics.sync,
                    currentness = (1f - abs(lineIndex - shown)).coerceIn(0f, 1f),
                    position = position,
                )
            }
        }
        Text(
            text = lyrics.provider.label,
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun LyricSlot(
    line: LyricLine?,
    sync: LyricsSync,
    currentness: Float,
    position: State<Long>,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SlotHeight)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (line == null) return@Box
        val sungColor = lerp(DimColor, Color.White, currentness)
        // Per-word fade levels (0..FADE_STEPS), derived so the slot recomposes only when a
        // level actually changes: a few times per word while one is fading in, never between.
        // Only the line being sung needs the moving position.
        val active = currentness > 0f
        val wordLevels = sync == LyricsSync.WORD && line.segments.isNotEmpty()
        val levels by remember(line, sync, active) {
            derivedStateOf {
                val pos = position.value
                when {
                    wordLevels && active -> line.segments.map { (wordFade(it, pos) * FADE_STEPS).toInt() }
                    wordLevels -> emptyList()
                    pos >= line.startMs -> listOf(FADE_STEPS)
                    else -> emptyList()
                }
            }
        }
        val text = remember(line, levels, sungColor, wordLevels) {
            buildAnnotatedString {
                append(line.text)
                if (wordLevels) {
                    line.segments.forEachIndexed { i, seg ->
                        val level = levels.getOrElse(i) { 0 }
                        if (level > 0) {
                            val color = lerp(DimColor, sungColor, level / FADE_STEPS.toFloat())
                            addStyle(SpanStyle(color = color), seg.startChar, seg.endChar.coerceAtMost(line.text.length))
                        }
                    }
                } else if (levels.isNotEmpty()) {
                    addStyle(SpanStyle(color = sungColor), 0, line.text.length)
                }
            }
        }
        // Long lines wrap onto up to three lines, shrinking (never below 70%) until they
        // fit the slot rather than being cut off with an ellipsis.
        var shrink by remember(line.text) { mutableFloatStateOf(1f) }
        val size = (13f + 3f * currentness) * shrink
        Text(
            text = text,
            color = DimColor,
            fontSize = size.sp,
            lineHeight = (size * 1.15f).sp,
            textAlign = TextAlign.Center,
            softWrap = true,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                if (layout.hasVisualOverflow && shrink > MIN_SHRINK) {
                    shrink = (shrink * 0.92f).coerceAtLeast(MIN_SHRINK)
                }
            },
        )
    }
}
