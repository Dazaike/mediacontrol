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
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: ThemeViewModel,
) {
    val accent by viewModel.accent.collectAsStateWithLifecycle()
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
    }
}
