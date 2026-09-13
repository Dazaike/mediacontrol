package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText

/**
 * Shared shell for every secondary screen: list state, position indicator, and a plain
 * TimeText — structurally identical to [QueueScreen]'s own Scaffold, which is the one
 * screen confirmed to scroll correctly on hardware. A prior version wrapped TimeText in
 * a `clickable` Box (tap-time-to-go-back); that wrapper silently broke touch-drag
 * scrolling on this screen (drags were consumed as swipe-to-dismiss instead of list
 * scroll) even though the list itself used proper `item`/`items` rows. Removed rather
 * than risk reintroducing that regression; back navigation still works via the native
 * edge swipe-to-dismiss gesture.
 *
 * [content] emits directly into the [ScalingLazyListScope] (via `item`/`items`) — every
 * row must be its own lazy item so touch/rotary scrolling actually scrolls when the row
 * count overflows the screen.
 */
@Composable
fun SecondaryScaffold(
    navController: NavHostController,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}
