package com.mediacontrol.remote.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsCodecTest {
    @Test
    fun `round trip preserves lines segments and provider`() {
        val original = Lyrics(
            LyricsSync.WORD,
            listOf(
                LyricLine(1000, 2000, "héllo wörld 日本", listOf(LyricSegment(1000, 1500, 0, 5), LyricSegment(1500, 2000, 6, 11))),
                LyricLine(2000, 3000, "plain"),
            ),
            LyricsProvider.LYRICS_PLUS,
        )
        assertEquals(original, LyricsCodec.decode(LyricsCodec.encode(original)))
    }

    @Test
    fun `overlong lines and line counts are capped so encoding stays valid`() {
        val long = "x".repeat(LyricsCodec.MAX_LINE_CHARS + 100)
        val many = List(LyricsCodec.MAX_LINES + 10) { LyricLine(it * 10, it * 10 + 10, long) }
        val decoded = LyricsCodec.decode(LyricsCodec.encode(Lyrics(LyricsSync.LINE, many, LyricsProvider.LRCLIB)))!!
        assertEquals(LyricsCodec.MAX_LINES, decoded.lines.size)
        assertEquals(LyricsCodec.MAX_LINE_CHARS, decoded.lines[0].text.length)
    }

    @Test
    fun `garbage decodes to null`() {
        assertNull(LyricsCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(LyricsCodec.decode(ByteArray(0)))
    }
}
