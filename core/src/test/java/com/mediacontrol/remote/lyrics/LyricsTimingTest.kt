package com.mediacontrol.remote.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTimingTest {
    private val lines = listOf(
        LyricLine(1000, 2000, "a"),
        LyricLine(2000, 3000, "b"),
        LyricLine(5000, 6000, "c"),
    )

    @Test
    fun `currentLineIndex boundaries`() {
        assertEquals(-1, currentLineIndex(lines, 0))
        assertEquals(-1, currentLineIndex(lines, 999))
        assertEquals(0, currentLineIndex(lines, 1000))
        assertEquals(1, currentLineIndex(lines, 2000))
        assertEquals(1, currentLineIndex(lines, 4999))
        assertEquals(2, currentLineIndex(lines, 5000))
        assertEquals(2, currentLineIndex(lines, 999_999))
        assertEquals(-1, currentLineIndex(emptyList(), 5))
    }

    @Test
    fun `wordFade ramps up around a word's start and never trails it`() {
        val word = LyricSegment(1000, 1400, 0, 4)
        assertEquals(0f, wordFade(word, 900), 0.001f)
        assertEquals(0f, wordFade(word, 920), 0.001f)
        assertEquals(true, wordFade(word, 1000) in 0.3f..0.4f)
        assertEquals(true, wordFade(word, 1000) < wordFade(word, 1100))
        assertEquals(1f, wordFade(word, 1140), 0.001f)
        assertEquals(1f, wordFade(word, 60_000), 0.001f)
    }

    @Test
    fun `clock advances only while playing and is zero before an anchor`() {
        val clock = LyricsClock()
        assertEquals(0L, clock.positionAt(10_000))
        clock.anchor(5_000, playing = true, atElapsedMs = 1_000)
        assertEquals(5_000L, clock.positionAt(1_000))
        assertEquals(7_500L, clock.positionAt(3_500))
        clock.anchor(9_000, playing = false, atElapsedMs = 4_000)
        assertEquals(9_000L, clock.positionAt(60_000))
    }
}
