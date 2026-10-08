package com.mediacontrol.remote.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.mediacontrol.remote.data.SessionAction

/**
 * Swipe-right page: whatever extra controls the active player exposes on the phone
 * (favourite, shuffle, ...). Labels and icons come from the player itself, so the list
 * differs per app and can change as the player updates (e.g. "Like" becoming "Unlike").
 */
@Composable
fun PlayerControlsPage(vm: NowPlayingViewModel) {
    val actions by vm.sessionActions.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    if (actions.isEmpty()) {
        Text(
            text = "This player has no extra controls",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        return
    }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ListHeader { Text("Player controls") } }
        items(actions, key = { it.id }) { action ->
            ActionButton(
                action = action,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.runAction(action.id)
                },
            )
        }
    }
}

@Composable
private fun ActionButton(action: SessionAction, onClick: () -> Unit) {
    val icon = remember(action.icon) {
        action.icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = Glass.buttonColors(),
        border = Glass.Border,
        icon = icon?.let { bitmap ->
            {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        label = { Text(action.label, maxLines = 2) },
    )
}
