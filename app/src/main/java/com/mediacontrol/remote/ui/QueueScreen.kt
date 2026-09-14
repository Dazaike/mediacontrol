package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

@Composable
fun QueueScreen(
    navController: NavHostController,
    viewModel: QueueViewModel,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    SecondaryScaffold(navController = navController) {
        item {
            ListHeader {
                Text(ui.queueTitle)
            }
        }

        ui.currentTitle?.let { current ->
            item {
                Text(
                    text = "Now: $current",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!ui.available) {
            item {
                Text(
                    text = "Queue unavailable for this player",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            itemsIndexed(ui.items) { index, track ->
                val isCurrent = track.title == ui.currentTitle &&
                    (ui.currentArtist.isNullOrEmpty() || track.artist.isNullOrEmpty() || track.artist == ui.currentArtist)
                FilledTonalButton(
                    onClick = { viewModel.skipTo(track.queueId) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isCurrent) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                    label = {
                        Text(
                            text = if (isCurrent) "▶ ${track.title}" else "${index + 1}. ${track.title}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    secondaryLabel = track.artist?.let { artist ->
                        {
                            Text(
                                text = artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
            }
        }
    }
}
