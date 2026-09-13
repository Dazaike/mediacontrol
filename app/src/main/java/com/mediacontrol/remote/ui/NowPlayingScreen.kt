package com.mediacontrol.remote.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.mediacontrol.remote.data.ControlledSession
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun NowPlayingScreen(
    vm: NowPlayingViewModel,
    onOpenPlayers: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    statusLine: String? = null,
    banner: Pair<String, String>? = null,
    onDismissBanner: () -> Unit = {},
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val artworkBytes by vm.artworkBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    TrackChangeHaptic(session = (uiState as? NowPlayingUiState.Ready)?.session)

    Scaffold(
        timeText = { TimeText() },
    ) {
        val pagerState = rememberPagerState(pageCount = { 2 })
        val pageIndicatorState = remember {
            object : PageIndicatorState {
                override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
                override val selectedPage: Int get() = pagerState.currentPage
                override val pageCount: Int get() = 2
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                // No inset: content (progress ring, artwork, controls) is meant to bleed
                // to the true screen edge, so the outer container carries zero padding.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 0.dp, vertical = 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (page == 0) {
                        when (val state = uiState) {
                            NowPlayingUiState.Loading -> {
                                Text("Loading…", style = MaterialTheme.typography.body2)
                            }

                            NowPlayingUiState.NoSession -> {
                                NoSessionContent(onOpenPlayers = onOpenPlayers)
                            }

                            is NowPlayingUiState.Ready -> {
                                val session = state.session
                                ReadyContent(
                                    session = session,
                                    artworkBytes = artworkBytes,
                                    statusLine = statusLine,
                                    banner = banner,
                                    onDismissBanner = onDismissBanner,
                                    onToggle = { vm.toggle() },
                                    onPrevious = { vm.previous() },
                                    onNext = { vm.next() },
                                    onOpenVolume = onOpenVolume,
                                    onOpenQueue = onOpenQueue,
                                )
                            }
                        }
                    } else {
                        MoreActionsContent(onOpenPlayers = onOpenPlayers, onOpenSettings = onOpenSettings)
                    }
                }
            }
            HorizontalPageIndicator(
                pageIndicatorState = pageIndicatorState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ReadyContent(
    session: ControlledSession,
    artworkBytes: ByteArray?,
    statusLine: String?,
    banner: Pair<String, String>?,
    onDismissBanner: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val context = LocalContext.current
    val artBitmap = remember(artworkBytes) {
        artworkBytes?.let { bytes ->
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    // 1s ticker for progress; never triggers infinite spinning vsync loop
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.isPlaying) {
        if (session.isPlaying) {
            while (true) {
                delay(1_000)
                now = android.os.SystemClock.elapsedRealtime()
            }
        }
    }
    val snapshotAt = remember(session) { android.os.SystemClock.elapsedRealtime() }
    val duration = session.durationMs
    val position = if (session.isPlaying && duration > 0) {
        min(session.positionMs + (now - snapshotAt), duration)
    } else {
        session.positionMs
    }

    // Outer progress ring: fill entire screen circumference.
    // Static progress=0f when duration is 0 (NEVER spin indeterminate animation).
    CircularProgressIndicator(
        progress = if (duration > 0) (position.coerceIn(0L, duration) / duration.toFloat()) else 0f,
        modifier = Modifier.fillMaxSize(),
        strokeWidth = 3.dp,
    )
    // Full-bleed album art background with smooth dark scrim for readability
    if (artBitmap != null) {
        Image(
            bitmap = artBitmap,
            contentDescription = "Album art",
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.65f)),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onToggle() })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(modifier = Modifier.height(18.dp))

        // Title and artist with edge padding and marquee scrolling for long names
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp),
        ) {
            Text(
                text = session.title ?: session.appLabel,
                style = MaterialTheme.typography.title3.copy(fontSize = 15.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            session.artist?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.caption2.copy(fontSize = 12.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
        }

        // Theme-driven accent: reacts live to the user's chosen accent color.
        val activeBtnBg = MaterialTheme.colors.primary
        val activeBtnContent = MaterialTheme.colors.onPrimary
        val playBtnBg = MaterialTheme.colors.secondary
        val playBtnContent = MaterialTheme.colors.onSecondary

        // Primary transport controls (center)
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = hapticClick(context, onPrevious),
                modifier = Modifier.size(46.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = activeBtnBg,
                    contentColor = activeBtnContent,
                ),
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            Button(
                onClick = hapticClick(context, onToggle),
                modifier = Modifier.size(56.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = playBtnBg,
                    contentColor = playBtnContent,
                ),
            ) {
                Icon(
                    if (session.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (session.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(28.dp),
                )
            }
            Button(
                onClick = hapticClick(context, onNext),
                modifier = Modifier.size(46.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = activeBtnBg,
                    contentColor = activeBtnContent,
                ),
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }

        // Bottom section: status line or banner, plus apps/volume/queue/settings buttons
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (banner != null) {
                Text(
                    text = "${banner.first} — ${banner.second}",
                    style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.clickable { onDismissBanner() },
                )
            } else if (statusLine != null) {
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                CompactButton(
                    onClick = hapticClick(context, onOpenVolume),
                    modifier = Modifier.size(34.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = activeBtnBg,
                        contentColor = activeBtnContent,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Volume",
                        modifier = Modifier.size(16.dp),
                    )
                }
                CompactButton(
                    onClick = hapticClick(context, onOpenQueue),
                    modifier = Modifier.size(34.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = activeBtnBg,
                        contentColor = activeBtnContent,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Queue",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun NoSessionContent(onOpenPlayers: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Nothing playing on phone",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
        )
        Button(onClick = hapticClick(context, onOpenPlayers)) {
            Text("Open player")
        }
    }
}

/** Second page, reached by swiping right off Now Playing: app switching and settings,
 *  moved off the primary screen so the transport controls stay uncluttered. */
@Composable
private fun MoreActionsContent(onOpenPlayers: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Button(
            onClick = hapticClick(context, onOpenPlayers),
            modifier = Modifier.size(52.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Apps, contentDescription = "Switch app", modifier = Modifier.size(24.dp))
        }
        Text("Apps", style = MaterialTheme.typography.caption2)
        Button(
            onClick = hapticClick(context, onOpenSettings),
            modifier = Modifier.size(52.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(24.dp))
        }
        Text("Settings", style = MaterialTheme.typography.caption2)
    }
}

@Composable
private fun TrackChangeHaptic(session: ControlledSession?) {
    val context = LocalContext.current
    val trackKey = session?.let { Triple(it.packageName, it.title, it.artist) }
    var previousKey by remember { mutableStateOf<Triple<String, String?, String?>?>(null) }
    LaunchedEffect(trackKey) {
        if (trackKey != null && previousKey != null && trackKey != previousKey) {
            buzz(context)
        }
        previousKey = trackKey
    }
}

/** Wraps a click action with a crisp, decorative haptic tap; never lets a haptic failure block the action. */
private fun hapticClick(context: Context, action: () -> Unit): () -> Unit = {
    buzz(context)
    action()
}

private fun buzz(context: Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) {
        // Haptics are decorative; failure must never break controls.
    }
}
