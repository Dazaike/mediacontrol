package com.mediacontrol.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.foundation.SwipeToDismissValue
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.mediacontrol.remote.data.AutoStartRepository
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.CuratedPlayersRepository

object Routes {
    const val NOW_PLAYING = "nowPlaying"
    const val VOLUME = "volume"
    const val PLAYERS = "players"
    const val SETTINGS = "settings"
    const val ADD_PLAYERS = "addPlayers"
    const val LYRICS_SOURCE = "lyricsSource"
}

/** Full-screen panels that slide in vertically over Now Playing. */
private enum class Panel { QUEUE, LYRICS }

private const val PANEL_MS = 280

// Lyrics are read at a glance in the dark, so their backdrop is dimmed harder than the rest.
private const val LYRICS_DIM = 0.9f

@Composable
fun NavGraph(mediaSource: CombinedMediaSource, ambient: Boolean = false) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val themeVm: ThemeViewModel = viewModel(factory = ThemeViewModelFactory(appContext))
    val curatedRepo = remember(appContext) { CuratedPlayersRepository(appContext) }
    val autoStartRepo = remember(appContext) { AutoStartRepository(appContext) }
    val lyricsVm: LyricsViewModel = viewModel(factory = LyricsViewModelFactory(mediaSource, appContext))
    val queueVm: QueueViewModel = viewModel(factory = QueueViewModelFactory(mediaSource))
    val stayVm: StayOpenViewModel = viewModel(factory = StayOpenViewModelFactory(appContext))
    val accent by themeVm.accent.collectAsStateWithLifecycle()
    val colorScheme = remember(accent) { accent.toColorScheme() }
    val backdrop = rememberBackdropBitmap(mediaSource.artworkBytes)

    // The navigation host only offers its fixed horizontal transitions, so the queue
    // (swipe up) and lyrics (swipe down) are panels with their own vertical slide. A
    // swipe right from the left edge (or the back key) closes them.
    var panel by rememberSaveable { mutableStateOf<Panel?>(null) }
    var pickingLyricsSource by rememberSaveable { mutableStateOf(false) }
    // After an edge swipe the panel is already off-screen to the right, so it must not
    // also play its vertical exit.
    var swipeClosed by remember { mutableStateOf(false) }
    val openPanel = { target: Panel ->
        swipeClosed = false
        panel = target
    }
    val closePanel = {
        if (pickingLyricsSource) {
            pickingLyricsSource = false
        } else {
            swipeClosed = true
            panel = null
        }
    }
    BackHandler(enabled = panel != null) {
        if (pickingLyricsSource) pickingLyricsSource = false else panel = null
    }

    MaterialTheme(colorScheme = colorScheme, motionScheme = MotionScheme.standard()) {
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            Box(modifier = Modifier.fillMaxSize()) {
                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = Routes.NOW_PLAYING,
                ) {
                    composable(Routes.NOW_PLAYING) {
                        val vm: NowPlayingViewModel =
                            viewModel(factory = NowPlayingViewModelFactory(mediaSource, autoStartRepo))
                        val onOpenPlayers = remember(navController) { { navController.navigate(Routes.PLAYERS) } }
                        val onOpenVolume = remember(navController) { { navController.navigate(Routes.VOLUME) } }
                        val onOpenSettings = remember(navController) { { navController.navigate(Routes.SETTINGS) } }
                        NowPlayingScreen(
                            vm = vm,
                            onOpenPlayers = onOpenPlayers,
                            onOpenVolume = onOpenVolume,
                            onOpenQueue = { openPanel(Panel.QUEUE) },
                            onOpenSettings = onOpenSettings,
                            onOpenLyrics = { openPanel(Panel.LYRICS) },
                            panelOpen = panel != null,
                        )
                    }
                    composable(Routes.VOLUME) {
                        val vm: VolumeViewModel = viewModel(factory = VolumeViewModelFactory(mediaSource))
                        VolumeScreen(navController = navController, viewModel = vm)
                    }
                    composable(Routes.PLAYERS) {
                        val vm: PlayersViewModel =
                            viewModel(factory = PlayersViewModelFactory(mediaSource, curatedRepo, appContext))
                        PlayersScreen(navController = navController, viewModel = vm)
                    }
                    composable(Routes.ADD_PLAYERS) {
                        val vm: AddPlayersViewModel =
                            viewModel(factory = AddPlayersViewModelFactory(mediaSource, curatedRepo))
                        AddPlayersScreen(navController = navController, viewModel = vm)
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            navController = navController,
                            viewModel = themeVm,
                            lyricsViewModel = lyricsVm,
                            stayOpenViewModel = stayVm,
                        )
                    }
                    composable(Routes.LYRICS_SOURCE) {
                        LyricsSourceScreen(
                            navController = navController,
                            viewModel = lyricsVm,
                            onDone = { navController.popBackStack() },
                        )
                    }
                }

                // Swipe up: the queue rises from the bottom.
                AnimatedVisibility(
                    visible = panel == Panel.QUEUE,
                    enter = slideInVertically(tween(PANEL_MS)) { it },
                    exit = if (swipeClosed) ExitTransition.None else slideOutVertically(tween(PANEL_MS)) { it },
                ) {
                    PanelSurface(onDismiss = closePanel) {
                        QueueScreen(navController = navController, viewModel = queueVm)
                    }
                }

                // Swipe down: the lyrics drop in from the top.
                AnimatedVisibility(
                    visible = panel == Panel.LYRICS,
                    enter = slideInVertically(tween(PANEL_MS)) { -it },
                    exit = if (swipeClosed) ExitTransition.None else slideOutVertically(tween(PANEL_MS)) { -it },
                ) {
                    // Keyed so the swipe state resets when the source picker opens or closes.
                    key(pickingLyricsSource) {
                        PanelSurface(onDismiss = closePanel, dim = LYRICS_DIM) {
                            if (pickingLyricsSource) {
                                LyricsSourceScreen(
                                    navController = navController,
                                    viewModel = lyricsVm,
                                    onDone = { pickingLyricsSource = false },
                                )
                            } else {
                                LyricsPage(
                                    vm = lyricsVm,
                                    isActive = !ambient,
                                    onOpenSource = { pickingLyricsSource = true },
                                )
                            }
                        }
                    }
                }

                if (ambient) AmbientScreen(mediaSource)
            }
        }
    }
}

/**
 * Opaque, focus-owning container for a [Panel] with the shared frosted backdrop. Swiping
 * right from the left edge dismisses it, like a normal Wear screen; [onDismiss] runs once the
 * swipe completes.
 */
@Composable
private fun PanelSurface(onDismiss: () -> Unit, dim: Float = 0.72f, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissValue.Dismissed) onDismiss()
    }
    BasicSwipeToDismissBox(state = state) { isBackground ->
        // The background (Now Playing, already on screen beneath) needs no content of its own.
        if (!isBackground) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .hierarchicalFocusGroup(active = true),
            ) {
                Backdrop(dim)
                content()
            }
        }
    }
}
