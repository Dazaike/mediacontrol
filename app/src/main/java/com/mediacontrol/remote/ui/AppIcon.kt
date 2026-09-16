package com.mediacontrol.remote.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val ICON_RETRIES = 5

/**
 * A phone app's launcher icon. The watch has none of these packages installed, so the
 * bytes come from the phone's icon catalog over the Data Layer.
 *
 * The catalog can land after first composition, so a miss is retried a few times
 * before giving up; until then the slot holds its size so rows do not jump.
 */
@Composable
fun AppIcon(
    packageName: String,
    load: suspend (String) -> ByteArray?,
    size: Dp = 24.dp,
) {
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        repeat(ICON_RETRIES) { attempt ->
            val decoded = withContext(Dispatchers.Default) {
                load(packageName)?.let { bytes ->
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                        while (maxDim / sample > 48) sample *= 2
                        val opts = BitmapFactory.Options().apply {
                            inJustDecodeBounds = false
                            inSampleSize = sample
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            if (decoded != null) {
                bitmap = decoded
                return@LaunchedEffect
            }
            if (attempt < ICON_RETRIES - 1) delay(1_000)
        }
    }
    val icon = bitmap
    if (icon == null) {
        Spacer(modifier = Modifier.size(size))
    } else {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(percent = 50)),
        )
    }
}
