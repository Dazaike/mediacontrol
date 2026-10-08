package com.mediacontrol.remote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonColors
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.IconButtonColors
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme

/**
 * "Frosted glass" for a small, mostly-dark OLED: surfaces are a faint veil over the dimmed
 * album backdrop plus a light-catching hairline edge. Veils stay well under half opacity
 * so the UI reads dark and glassy rather than grey or light.
 */
object Glass {
    private val Veil = Color.White.copy(alpha = 0.10f)
    private val VeilStrong = Color.White.copy(alpha = 0.16f)

    /** Hairline brighter at the top-left, as if lit from above. */
    val Border = BorderStroke(
        1.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f))),
    )

    /**
     * Glass button colours. [veil] is the container colour; the text, secondary text and
     * icon colours are set explicitly because the button defaults assume a solid accent
     * container and would otherwise leave the secondary line (e.g. an artist) dark.
     */
    @Composable
    fun buttonColors(veil: Color = Veil): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = veil,
        contentColor = MaterialTheme.colorScheme.onSurface,
        secondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        iconColor = MaterialTheme.colorScheme.onSurface,
    )

    @Composable
    fun buttonColors(strong: Boolean): ButtonColors = buttonColors(if (strong) VeilStrong else Veil)

    /** Queue rows: noticeably more tinted than the default veil, accent-tinted when current. */
    @Composable
    fun queueColors(current: Boolean): ButtonColors = buttonColors(
        if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.20f),
    )

    @Composable
    fun iconButtonColors(strong: Boolean = false): IconButtonColors =
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (strong) VeilStrong else Veil,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
}
