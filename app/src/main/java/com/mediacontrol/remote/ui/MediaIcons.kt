package com.mediacontrol.remote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The twelve Material icons this app actually draws, defined locally from their
 * official 24dp path data.
 *
 * `compose-material-icons-extended` ships ~2000 icons as an 87 MB jar; unshrunk it
 * dominated the debug APK and its dex load cost seconds of cold start. Nothing here
 * is generated at build time — each vector is built once, lazily, on first use.
 *
 * Fill is opaque white because [androidx.wear.compose.material3.Icon] re-tints with
 * the current content color; the declared fill is never what ends up on screen.
 */
private fun icon(
    name: String,
    vararg paths: String,
    autoMirror: Boolean = false,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).apply {
    val fill = SolidColor(Color.White)
    for (data in paths) {
        addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = fill)
    }
}.build()

object MediaIcons {

    val PlayArrow: ImageVector by lazy { icon("PlayArrow", "M8 5v14l11-7z") }

    val Pause: ImageVector by lazy { icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z") }

    val SkipPrevious: ImageVector by lazy {
        icon("SkipPrevious", "M6 6h2v12H6zm3.5 6 8.5 6V6z")
    }

    val SkipNext: ImageVector by lazy {
        icon("SkipNext", "m6 18 8.5-6L6 6v12zM16 6v12h2V6h-2z")
    }

    val VolumeUp: ImageVector by lazy {
        icon(
            "VolumeUp",
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 7.97v8.05c1.48-.73 2.5-2.25 " +
                "2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 " +
                "7-4.49 7-8.77s-2.99-7.86-7-8.77z",
            autoMirror = true,
        )
    }

    val VolumeDown: ImageVector by lazy {
        icon(
            "VolumeDown",
            "M18.5 12A4.5 4.5 0 0 0 16 7.97v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 9v6h4l5 5V4L9 9H5z",
            autoMirror = true,
        )
    }

    val QueueMusic: ImageVector by lazy {
        icon(
            "QueueMusic",
            "M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 " +
                "0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z",
            autoMirror = true,
        )
    }

    val Apps: ImageVector by lazy {
        icon(
            "Apps",
            "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 " +
                "4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z",
        )
    }

    val Settings: ImageVector by lazy {
        icon(
            "Settings",
            "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 " +
                ".12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 " +
                "0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 " +
                "0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 " +
                "1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 " +
                "2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 " +
                "0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 " +
                "3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
        )
    }

    val Hearing: ImageVector by lazy {
        icon(
            "Hearing",
            "M17 20c-.29 0-.56-.06-.76-.15-.71-.37-1.21-.88-1.71-2.38-.51-1.56-1.47-2.29-2.39-3-.79-.61-1.61-1.24-2.32-2.53C9.29 " +
                "10.98 9 9.93 9 9c0-2.8 2.2-5 5-5s5 2.2 5 5h2c0-3.93-3.07-7-7-7S7 5.07 7 9c0 1.26.38 2.65 " +
                "1.07 3.9.91 1.65 1.98 2.48 2.85 3.15.81.62 1.39 1.07 1.71 2.05.6 1.82 1.37 2.84 2.73 " +
                "3.55A3.999 3.999 0 0 0 21 18h-2c0 1.1-.9 2-2 2zM7.64 2.64 6.22 1.22C4.23 3.21 3 5.96 3 " +
                "9s1.23 5.79 3.22 7.78l1.41-1.41C6.01 13.74 5 11.49 5 9s1.01-4.74 2.64-6.36zM11.5 9a2.5 " +
                "2.5 0 0 0 5 0 2.5 2.5 0 0 0-5 0z",
        )
    }

    val NoiseAware: ImageVector by lazy {
        icon(
            "NoiseAware",
            "M16 15h-2a1.003 1.003 0 0 1-1.95.32c-.15-.44-.4-1.08-.93-1.61l-1.36-1.36C9.28 11.87 9 " +
                "11.19 9 10.5a2.5 2.5 0 0 1 4.95-.5h2.02c-.25-2.25-2.16-4-4.47-4C9.02 6 7 8.02 7 " +
                "10.5c0 1.22.49 2.41 1.35 3.27l1.36 1.36c.17.17.31.44.44.82A3.013 3.013 0 0 0 13 " +
                "18c1.65 0 3-1.35 3-3z",
            CIRCLE_13_5_12_5,
            "m3.6 6.58 1.58 1.26c.35-.57.77-1.1 1.24-1.57L4.85 5.02c-.47.47-.88 1-1.25 1.56zm5.86-2.16-.87-1.81c-.63.23-1.24.52-1.8.87l.87 " +
                "1.81c.56-.36 1.16-.65 1.8-.87zM4.49 9.26l-1.96-.45c-.21.63-.36 1.28-.44 1.95l1.96.45a7.9 " +
                "7.9 0 0 1 .44-1.95zM20.4 6.58a9.4 9.4 0 0 0-1.25-1.56l-1.58 1.26c.48.47.89.99 1.24 " +
                "1.57l1.59-1.27zM4.04 12.79l-1.96.45c.08.67.23 1.33.44 1.95l1.97-.45c-.22-.62-.38-1.27-.45-1.95zm13.17-9.31c-.57-.35-1.17-.64-1.8-.87l-.87 " +
                "1.81c.64.22 1.24.51 1.8.87l.87-1.81zM13 4.07V2.05c-.33-.03-.66-.05-1-.05s-.67.02-1 " +
                ".05v2.02c.33-.04.66-.07 1-.07s.67.03 1 .07zm-2 15.86v2.02c.33.03.66.05 1 " +
                ".05s.67-.02 1-.05v-2.02c-.33.04-.66.07-1 .07s-.67-.03-1-.07zm8.51-5.19 1.97.45c.21-.63.36-1.28.44-1.95l-1.96-.45c-.07.68-.23 " +
                "1.33-.45 1.95zm.45-3.53 1.96-.45a9.69 9.69 0 0 0-.44-1.95l-1.97.45c.22.62.38 1.27.45 " +
                "1.95zm-2.38 6.52 1.58 1.26c.47-.48.88-1 1.25-1.56l-1.58-1.26a9.4 9.4 0 0 " +
                "1-1.25 1.56zM6.79 20.52c.57.35 1.17.64 1.8.87l.87-1.81c-.64-.22-1.24-.51-1.8-.87l-.87 " +
                "1.81zm7.75-.94.87 1.81c.63-.23 1.24-.52 1.8-.87l-.87-1.81c-.56.36-1.16.65-1.8.87zM3.6 " +
                "17.42a9.4 9.4 0 0 0 1.25 1.56l1.58-1.26a7.87 7.87 0 0 1-1.24-1.57L3.6 17.42z",
        )
    }

    val NoiseControlOff: ImageVector by lazy {
        icon(
            "NoiseControlOff",
            "M12 4c1.44 0 2.79.38 3.95 1.05L17.4 3.6C15.85 2.59 13.99 2 12 2s-3.85.59-5.41 " +
                "1.59l1.45 1.45A8.034 8.034 0 0 1 12 4zm8 8c0 1.44-.38 2.79-1.05 3.95l1.45 " +
                "1.45c1.01-1.55 1.6-3.41 1.6-5.4s-.59-3.85-1.59-5.41l-1.45 1.45A8.034 8.034 0 0 1 20 " +
                "12zm-8 8c-1.44 0-2.79-.38-3.95-1.05L6.6 20.4C8.15 21.41 10.01 22 12 22s3.85-.59 " +
                "5.41-1.59l-1.45-1.45A8.034 8.034 0 0 1 12 20zm-8-8c0-1.44.38-2.79 1.05-3.95L3.59 " +
                "6.59C2.59 8.15 2 10.01 2 12s.59 3.85 1.59 5.41l1.45-1.45A8.034 8.034 0 0 1 4 " +
                "12zm7.5-6C9.02 6 7 8.02 7 10.5c0 1.22.49 2.41 1.35 3.27l1.36 1.36c.17.17.31.44.44.82A3.013 " +
                "3.013 0 0 0 13 18c1.65 0 3-1.35 3-3h-2a1.003 1.003 0 0 1-1.95.32c-.15-.44-.4-1.08-.93-1.61l-1.36-1.36C9.28 " +
                "11.87 9 11.19 9 10.5a2.5 2.5 0 0 1 4.95-.5h2.02c-.25-2.25-2.16-4-4.47-4z",
            CIRCLE_13_5_12_5,
        )
    }
}

/** The `<circle cx="13.5" cy="12.5" r="1.5"/>` both noise icons carry, as arc path data. */
private const val CIRCLE_13_5_12_5 = "M13.5 11a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z"
