package com.mediacontrol.remote.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val BackdropPx = 32

/** The decoded backdrop, provided once at the nav root so every screen draws the same one. */
val LocalBackdrop = compositionLocalOf<State<ImageBitmap?>?> { null }

/**
 * Decodes the current album art into a tiny bitmap. Frosted without a GPU blur: shrink once,
 * and bilinear upscaling does the softening. A full-screen RenderEffect blur was costing
 * the watch's GPU a frame budget per redraw.
 */
@Composable
fun rememberBackdropBitmap(artwork: StateFlow<ByteArray?>): State<ImageBitmap?> {
    val bytes by artwork.collectAsStateWithLifecycle()
    val bitmap = remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap.value = withContext(Dispatchers.Default) { bytes?.let(::decodeBackdrop) }
    }
    return bitmap
}

private fun decodeBackdrop(bytes: ByteArray): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / (sample * 2) >= BackdropPx * 2) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { decoded ->
        Bitmap.createScaledBitmap(decoded, BackdropPx, BackdropPx, true)
            .also { if (it !== decoded) decoded.recycle() }
            .asImageBitmap()
    }
} catch (_: Exception) {
    null
}

/** Dimmed, softened album art behind a screen; draws nothing until art arrives. */
@Composable
fun Backdrop(dim: Float = 0.72f) {
    val art by (LocalBackdrop.current ?: return)
    // Crossfade rather than snap: the decode lands asynchronously, so a hard swap
    // reads as a flash on track change.
    Crossfade(targetState = art, animationSpec = tween(500), label = "artwork") { image ->
        if (image != null) {
            // The dim rides along as a colour filter rather than a second full-screen Box.
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                colorFilter = ColorFilter.tint(Color.Black.copy(alpha = dim), BlendMode.SrcAtop),
            )
        }
    }
}
