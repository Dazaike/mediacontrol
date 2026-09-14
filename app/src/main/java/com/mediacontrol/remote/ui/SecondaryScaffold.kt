package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState

/**
 * Shared shell for every secondary screen.
 * Back navigation still works via the native edge swipe-to-dismiss gesture.
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
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
