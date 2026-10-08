package com.mediacontrol.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import com.mediacontrol.remote.lyrics.LyricsProvider
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import com.mediacontrol.remote.data.STAY_OPEN_CHOICES
import com.mediacontrol.remote.data.stayOpenLabel
import androidx.wear.compose.material3.Text

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: ThemeViewModel,
    lyricsViewModel: LyricsViewModel,
    stayOpenViewModel: StayOpenViewModel,
) {
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val lyricsProvider by lyricsViewModel.provider.collectAsStateWithLifecycle()
    val stayMinutes by stayOpenViewModel.minutes.collectAsStateWithLifecycle()
    SecondaryScaffold(navController = navController) {
        item {
            ListHeader {
                Text("Accent Color")
            }
        }
        items(AccentColor.entries) { option ->
            RadioButton(
                selected = option == accent,
                onSelect = { viewModel.setAccent(option) },
                modifier = Modifier.fillMaxWidth(),
                icon = {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(option.primary),
                    )
                },
                label = { Text(option.label) },
            )
        }
        item {
            Button(
                onClick = { navController.navigate(Routes.LYRICS_SOURCE) },
                modifier = Modifier.fillMaxWidth(),
                colors = Glass.buttonColors(),
                border = Glass.Border,
                label = { Text("Lyrics source") },
                secondaryLabel = { Text((lyricsProvider ?: LyricsProvider.AUTO).label) },
            )
        }
        item {
            ListHeader {
                Text("Stay open")
            }
        }
        item {
            Text(
                text = "Keep the player up after the screen turns off",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        items(STAY_OPEN_CHOICES) { minutes ->
            RadioButton(
                selected = minutes == stayMinutes,
                onSelect = { stayOpenViewModel.setMinutes(minutes) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stayOpenLabel(minutes)) },
            )
        }
    }
}
