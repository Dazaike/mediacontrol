package com.mediacontrol.remote.lyrics

enum class LyricsSync { PLAIN, LINE, WORD }

/** One timed word or syllable; [endChar] is exclusive and indexes into [LyricLine.text]. */
data class LyricSegment(val startMs: Int, val endMs: Int, val startChar: Int, val endChar: Int)

/** [segments] is empty unless the owning [Lyrics.sync] is [LyricsSync.WORD]. */
data class LyricLine(
    val startMs: Int,
    val endMs: Int,
    val text: String,
    val segments: List<LyricSegment> = emptyList(),
)

data class Lyrics(val sync: LyricsSync, val lines: List<LyricLine>, val provider: LyricsProvider)

/** [AUTO] is only a request value; it is never the [Lyrics.provider] of a result. */
enum class LyricsProvider(val label: String) {
    AUTO("Auto"),
    LRCLIB("LRCLIB"),
    KUGOU("KuGou"),
    LRC_RED("LRC.red"),
    BETTER_LYRICS("BetterLyrics"),
    LYRICS_PLUS("LyricsPlus"),
    GENIUS("Genius"),
}

data class LyricsQuery(val title: String, val artist: String, val durationMs: Long)
