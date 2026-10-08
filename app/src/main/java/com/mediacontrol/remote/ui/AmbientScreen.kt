package com.mediacontrol.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.mediacontrol.remote.data.CombinedMediaSource

/**
 * Low-power face drawn while the wrist is down and the app is being kept open: pure
 * black with dim text only, so the always-on OLED stays cheap and burn-in safe.
 */
@Composable
fun AmbientScreen(mediaSource: CombinedMediaSource) {
    val session by mediaSource.activeSession.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val s = session
        if (s != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 36.dp),
            ) {
                Text(
                    text = s.title ?: s.appLabel,
                    color = Color(0xFFBDBDBD),
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                s.artist?.let {
                    Text(
                        text = it,
                        color = Color(0xFF757575),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
