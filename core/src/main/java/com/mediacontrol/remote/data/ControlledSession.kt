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

/** One item in a media player's queue (e.g. Spotify Play Queue). */
data class QueueTrack(
    val queueId: Long,
    val title: String,
    val artist: String?,
)
