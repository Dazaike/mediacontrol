package com.mediacontrol.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.Text
import kotlin.math.roundToInt

@Composable
fun VolumeScreen(
    navController: NavHostController,
    viewModel: VolumeViewModel,
) {
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val maxVolume by viewModel.maxVolume.collectAsStateWithLifecycle()
    val max = maxVolume.coerceAtLeast(1)
    val current = volume.coerceIn(0, max)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        LevelIndicator(
            value = { current.toFloat() / max.toFloat() },
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Stepper(
            value = current.toFloat(),
            onValueChange = { viewModel.setVolume(it.roundToInt()) },
            steps = (max - 1).coerceAtLeast(0),
            valueRange = 0f..max.toFloat(),
            decreaseIcon = {
                Icon(MediaIcons.VolumeDown, contentDescription = "Lower")
            },
            increaseIcon = {
                Icon(MediaIcons.VolumeUp, contentDescription = "Raise")
            },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$current",
                    style = MaterialTheme.typography.numeralMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Phone Volume",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
