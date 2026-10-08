package com.mediacontrol.remote.lyrics

import com.mediacontrol.remote.data.interpolatePlaybackPosition

fun lyricsTrackKey(title: String?, artist: String?, durationMs: Long): String =
    "${title.orEmpty()}|${artist.orEmpty()}|${durationMs / 1000}"

/** Index of the last line with `startMs <= posMs`, or -1 before the first line. */
fun currentLineIndex(lines: List<LyricLine>, posMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].startMs <= posMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/** How far before a word's start it begins to light, and how long it takes to reach full. */
private const val FADE_LEAD_MS = 80L
private const val FADE_MS = 220L

/**
 * How lit a word or syllable is at [posMs], from 0 (dim) to 1 (fully lit). Each word fades
 * in over a short ramp that begins just ahead of its start, so the highlight glides from
 * word to word instead of popping, without trailing the audio.
 */
fun wordFade(segment: LyricSegment, posMs: Long): Float =
    ((posMs - (segment.startMs - FADE_LEAD_MS)).toFloat() / FADE_MS).coerceIn(0f, 1f)

/** Playback clock anchored to the phone's reported position; thread-safe via an immutable swap. */
class LyricsClock {
    private class Anchor(val posMs: Long, val playing: Boolean, val atElapsedMs: Long)

    @Volatile
    private var anchor: Anchor? = null

    fun anchor(posMs: Long, playing: Boolean, atElapsedMs: Long) {
        anchor = Anchor(posMs, playing, atElapsedMs)
    }

    fun positionAt(nowElapsedMs: Long): Long {
        val a = anchor ?: return 0L
        return interpolatePlaybackPosition(a.posMs, a.playing, nowElapsedMs - a.atElapsedMs, 0L)
    }
}
