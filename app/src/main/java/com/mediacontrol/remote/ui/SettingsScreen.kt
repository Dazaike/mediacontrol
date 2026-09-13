package com.mediacontrol.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: ThemeViewModel,
) {
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    SecondaryScaffold(navController = navController) {
        item {
            Text(
                "Accent Color",
                style = MaterialTheme.typography.title3,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(AccentColor.entries) { option ->
            Chip(
                onClick = { viewModel.setAccent(option) },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(option.primary),
                    )
                },
                label = { Text(option.label) },
                secondaryLabel = if (option == accent) {
                    { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                } else {
                    null
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
