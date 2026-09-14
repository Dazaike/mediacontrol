package com.mediacontrol.remote.data

import android.net.Uri

data class ControlledSession(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val artist: String?,
    val artworkUri: Uri?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volumeIgnoredHere: Boolean = true,
    val artworkBytes: ByteArray? = null,
)

/** Title/artist/playing/art — ignores position ticks that the watch interpolates locally. */
fun ControlledSession.sameUi(other: ControlledSession): Boolean =
    packageName == other.packageName &&
        appLabel == other.appLabel &&
        title == other.title &&
        artist == other.artist &&
        isPlaying == other.isPlaying &&
        durationMs == other.durationMs &&
        artworkBytes.contentEqualsOrBothNull(other.artworkBytes)

/**
 * True when the watch/phone should actually broadcast [next]. Position-only ticks that
 * match elapsed playback time are dropped; seeks and track/play changes pass.
 */
fun shouldPublishSession(
    previous: ControlledSession?,
    next: ControlledSession?,
    elapsedSincePublishMs: Long,
    positionJumpMs: Long = 2_500L,
): Boolean {
    if (previous == null || next == null) return previous != next
    if (!previous.sameUi(next)) return true
    val expected = previous.positionMs + if (previous.isPlaying) elapsedSincePublishMs.coerceAtLeast(0L) else 0L
    return kotlin.math.abs(next.positionMs - expected) > positionJumpMs
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    this === other || (this != null && other != null && contentEquals(other)) || (this == null && other == null)

/** One item in a media player's queue (e.g. Spotify Play Queue). */
data class QueueTrack(
    val queueId: Long,
    val title: String,
    val artist: String?,
)
