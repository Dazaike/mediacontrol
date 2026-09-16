package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
fun PlayersScreen(
    navController: NavHostController,
    viewModel: PlayersViewModel,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SecondaryScaffold(navController = navController) {
        item {
            ListHeader {
                Text("Players")
            }
        }
        if (ui.players.isEmpty()) {
            item {
                Text(
                    text = "No players yet",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(ui.players) { entry ->
            FilledTonalButton(
                onClick = {
                    viewModel.open(entry)
                    navController.popBackStack(Routes.NOW_PLAYING, false)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { AppIcon(packageName = entry.packageName, load = viewModel::icon) },
                label = { Text(if (entry.isPlaying) "▶ ${entry.label}" else entry.label) },
            )
        }
        item {
            FilledTonalButton(
                onClick = { navController.navigate(Routes.ADD_PLAYERS) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("Add apps") },
            )
        }
    }
}
