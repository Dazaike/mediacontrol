package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

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
            Text(
                "Players",
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (ui.known.isNotEmpty()) {
            item { Text("Known players", style = MaterialTheme.typography.caption2) }
            items(ui.known) { entry ->
                Chip(
                    onClick = { viewModel.open(entry.packageName) },
                    label = { Text(entry.label) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (ui.others.isNotEmpty()) {
            item { Text("Other audible apps", style = MaterialTheme.typography.caption2) }
            items(ui.others) { entry ->
                Chip(
                    onClick = { viewModel.open(entry.packageName) },
                    label = { Text(entry.label) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (ui.known.isEmpty() && ui.others.isEmpty()) {
            item {
                Text(
                    text = "No players found",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
