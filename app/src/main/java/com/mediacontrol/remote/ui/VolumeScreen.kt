package com.mediacontrol.remote.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.rotary.RotaryScrollableBehavior
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.CoroutineScope

/** Scroll pixels accumulated before they become one ±1 volume step. */
private const val STEP_PX = 24f

private class VolumeRotaryBehavior(
    private val onSteps: (Int) -> Unit,
) : RotaryScrollableBehavior {
    private var acc = 0f

    override suspend fun CoroutineScope.performScroll(
        timestampMillis: Long,
        delta: Float,
        inputDeviceId: Int,
        orientation: Orientation,
    ) {
        acc += delta
        val steps = (acc / STEP_PX).toInt()
        if (steps != 0) {
            acc -= steps * STEP_PX
            onSteps(steps)
        }
    }
}

@Composable
fun VolumeScreen(
    navController: NavHostController,
    viewModel: VolumeViewModel,
) {
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val maxVolume by viewModel.maxVolume.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val behavior = remember(viewModel) { VolumeRotaryBehavior(viewModel::nudge) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        timeText = { TimeText() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .rotaryScrollable(behavior, focusRequester),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Phone Volume",
                    style = MaterialTheme.typography.title3.copy(fontSize = 16.sp),
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "$volume / $maxVolume",
                    style = MaterialTheme.typography.body1.copy(fontSize = 14.sp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary,
                )

                Spacer(modifier = Modifier.height(14.dp))

                InlineSlider(
                    value = volume.coerceIn(0, maxVolume.coerceAtLeast(1)),
                    onValueChange = { viewModel.setVolume(it) },
                    valueProgression = 0..maxVolume.coerceAtLeast(1),
                    decreaseIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeDown,
                            contentDescription = "Lower",
                            tint = MaterialTheme.colors.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    increaseIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Raise",
                            tint = MaterialTheme.colors.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    colors = InlineSliderDefaults.colors(
                        backgroundColor = MaterialTheme.colors.primary,
                        selectedBarColor = MaterialTheme.colors.primary,
                        unselectedBarColor = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
