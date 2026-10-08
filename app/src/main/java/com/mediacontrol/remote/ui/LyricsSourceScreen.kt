package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import com.mediacontrol.remote.lyrics.LyricsProvider

/** Picks which provider the phone scrapes; choosing one re-requests lyrics immediately. */
@Composable
fun LyricsSourceScreen(
    navController: NavHostController,
    viewModel: LyricsViewModel,
    onDone: () -> Unit,
) {
    val current by viewModel.provider.collectAsStateWithLifecycle()
    SecondaryScaffold(navController = navController) {
        item {
            ListHeader {
                Text("Lyrics source")
            }
        }
        items(LyricsProvider.entries) { option ->
            RadioButton(
                selected = option == current,
                onSelect = {
                    viewModel.setProvider(option)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(option.label) },
            )
        }
    }
}
