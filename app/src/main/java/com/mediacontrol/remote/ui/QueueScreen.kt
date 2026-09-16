package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.mediacontrol.remote.data.QueueTrack
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

private fun isCurrentTrack(
    track: QueueTrack,
    currentTitle: String?,
    currentArtist: String?,
): Boolean = track.title == currentTitle &&
    (currentArtist.isNullOrEmpty() || track.artist.isNullOrEmpty() || track.artist == currentArtist)

@Composable
fun QueueScreen(
    navController: NavHostController,
    viewModel: QueueViewModel,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    var jumpedToCurrent by remember { mutableStateOf(false) }

    // Land on the queue centred on whatever is actively playing, rather than the
    // top of the list — a swipe-up into the queue should feel like it opened right
    // where playback is, not at track 1. Only the first non-empty snapshot after
    // opening triggers this so it doesn't yank the list back while the user is
    // browsing and the active track advances underneath them.
    LaunchedEffect(ui.available, ui.items, ui.currentTitle, ui.currentArtist) {
        if (jumpedToCurrent || !ui.available) return@LaunchedEffect
        val trackIndex = ui.items.indexOfFirst { isCurrentTrack(it, ui.currentTitle, ui.currentArtist) }
        if (trackIndex < 0) return@LaunchedEffect
        val headerItems = 1 + if (ui.currentTitle != null) 1 else 0
        listState.scrollToItem(headerItems + trackIndex)
        jumpedToCurrent = true
    }

    SecondaryScaffold(navController = navController, state = listState) {
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
                val isCurrent = isCurrentTrack(track, ui.currentTitle, ui.currentArtist)
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
