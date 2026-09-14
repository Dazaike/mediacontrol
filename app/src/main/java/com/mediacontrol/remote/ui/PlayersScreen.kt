package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    SecondaryScaffold(navController = navController) {
        item {
            ListHeader {
                Text("Players")
            }
        }
        if (ui.known.isNotEmpty()) {
            item {
                Text("Known players", style = MaterialTheme.typography.labelSmall)
            }
            items(ui.known) { entry ->
                FilledTonalButton(
                    onClick = { viewModel.open(entry.packageName) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(entry.label) },
                )
            }
        }
        if (ui.others.isNotEmpty()) {
            item {
                Text("Other audible apps", style = MaterialTheme.typography.labelSmall)
            }
            items(ui.others) { entry ->
                FilledTonalButton(
                    onClick = { viewModel.open(entry.packageName) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(entry.label) },
                )
            }
        }
        if (ui.known.isEmpty() && ui.others.isEmpty()) {
            item {
                Text(
                    text = "No players found",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
