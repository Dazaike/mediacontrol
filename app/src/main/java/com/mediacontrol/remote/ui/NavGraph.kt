package com.mediacontrol.remote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.mediacontrol.remote.data.CombinedMediaSource

object Routes {
    const val NOW_PLAYING = "nowPlaying"
    const val VOLUME = "volume"
    const val QUEUE = "queue"
    const val PLAYERS = "players"
    const val SETTINGS = "settings"
}

@Composable
fun NavGraph(mediaSource: CombinedMediaSource) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val themeVm: ThemeViewModel = viewModel(factory = ThemeViewModelFactory(appContext))
    val accent by themeVm.accent.collectAsStateWithLifecycle()
    val colors = remember(accent) {
        Colors(
            primary = accent.primary,
            primaryVariant = accent.primary,
            secondary = accent.secondary,
            secondaryVariant = accent.secondary,
            onPrimary = Color.White,
            onSecondary = Color.Black,
        )
    }
    MaterialTheme(colors = colors) {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = Routes.NOW_PLAYING,
        ) {
            composable(Routes.NOW_PLAYING) {
                val vm: NowPlayingViewModel =
                    viewModel(factory = NowPlayingViewModelFactory(mediaSource))
                val statusLine by vm.statusLine.collectAsStateWithLifecycle()
                val banner by vm.banner.collectAsStateWithLifecycle()
                NowPlayingScreen(
                    vm = vm,
                    onOpenPlayers = { navController.navigate(Routes.PLAYERS) },
                    onOpenVolume = { navController.navigate(Routes.VOLUME) },
                    onOpenQueue = { navController.navigate(Routes.QUEUE) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    statusLine = statusLine,
                    banner = banner,
                    onDismissBanner = { vm.dismissBanner() },
                )
            }
            composable(Routes.VOLUME) {
                val vm: VolumeViewModel = viewModel(factory = VolumeViewModelFactory(mediaSource))
                VolumeScreen(navController = navController, viewModel = vm)
            }
            composable(Routes.QUEUE) {
                val vm: QueueViewModel = viewModel(factory = QueueViewModelFactory(mediaSource))
                QueueScreen(navController = navController, viewModel = vm)
            }
            composable(Routes.PLAYERS) {
                val playersContext = LocalContext.current
                val app = remember(playersContext) { playersContext.applicationContext }
                val vm: PlayersViewModel =
                    viewModel(factory = PlayersViewModelFactory(mediaSource, app))
                PlayersScreen(navController = navController, viewModel = vm)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(navController = navController, viewModel = themeVm)
            }
        }
    }
}
