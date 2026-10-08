package com.mediacontrol.remote.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParsersTest {
    // ---- LRC ----

    @Test
    fun `lrc handles 2 and 3 digit fractions, multi-stamp lines and skips metadata and blanks`() {
        val lrc = """
            [ti:Song]
            [ar:Someone]
            [00:20.50] second
            [00:01.25][00:30.125] chorus
            [00:10.00]
            [00:05:07] colon fraction
        """.trimIndent()
        val l = LyricsParsers.parseLrc(lrc)!!
        assertEquals(LyricsSync.LINE, l.sync)
        assertEquals(
            listOf(1250 to "chorus", 5070 to "colon fraction", 20500 to "second", 30125 to "chorus"),
            l.lines.map { it.startMs to it.text },
        )
        assertEquals(5070, l.lines[0].endMs)
        assertEquals(30125 + 5000, l.lines.last().endMs)
    }

    @Test
    fun `lrc with no timed lines is null`() {
        assertNull(LyricsParsers.parseLrc("[ar:x]\njust text"))
    }

    @Test
    fun `kugou credit lines and title header are dropped`() {
        val lrc = "[00:00.00]Blinding Lights - The Weeknd\n[00:01.00]Lyrics by：Max\n[00:02.00]Composed by：Max\n[00:03.00]作词 : 某\n[00:04.00]Real line"
        val l = LyricsParsers.parseKugouLrc(lrc)!!
        assertEquals(listOf("Real line"), l.lines.map { it.text })
    }

    @Test
    fun `lrclib uses synced lyrics only`() {
        assertNull(LyricsParsers.parseLrclibJson("""{"plainLyrics":"abc","syncedLyrics":null}"""))
        val l = LyricsParsers.parseLrclibJson("""{"syncedLyrics":"[00:13.13] Yeah\n[00:27.67] Hi"}""")!!
        assertEquals(13130, l.lines[0].startMs)
    }

    // ---- TTML ----

    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
        <p begin="27.395" end="28.960"><span begin="27.395" end="27.549">I</span> <span begin="27.549" end="27.740">been</span> <span begin="27.740" end="28.960">tryna</span></p>
        <p begin="2:20.000" end="2:22.000"><span begin="2:20.000" end="2:20.500">e</span><span begin="2:20.500" end="2:21.000">ver</span> <span begin="2:21.000" end="2:21.500">more</span><span ttm:role="x-bg"><span begin="2:21.0" end="2:22.0">(Back)</span></span></p>
        </div></body></tt>
    """.trimIndent()

    @Test
    fun `ttml joins syllables without spaces, skips background vocals, and reports word sync`() {
        val l = LyricsParsers.parseTtml(ttml)!!
        assertEquals(LyricsSync.WORD, l.sync)
        assertEquals("I been tryna", l.lines[0].text)
        assertEquals(27395, l.lines[0].startMs)
        assertEquals(listOf(0 to 1, 2 to 6, 7 to 12), l.lines[0].segments.map { it.startChar to it.endChar })
        assertEquals("ever more", l.lines[1].text)
        assertEquals(140000, l.lines[1].startMs)
        assertEquals(3, l.lines[1].segments.size)
        assertEquals(0 to 1, l.lines[1].segments[0].let { it.startChar to it.endChar })
        assertEquals(1 to 4, l.lines[1].segments[1].let { it.startChar to it.endChar })
    }

    @Test
    fun `ttml paragraphs without timed spans become line sync`() {
        val xml = """<tt><body><div><p begin="1.0" end="2.0">one</p><p begin="3.0" end="4.0">two</p></div></body></tt>"""
        val l = LyricsParsers.parseTtml(xml)!!
        assertEquals(LyricsSync.LINE, l.sync)
        assertTrue(l.lines.all { it.segments.isEmpty() })
        assertEquals(listOf("one", "two"), l.lines.map { it.text })
    }

    @Test
    fun `ttml clock formats`() {
        assertEquals(27395, LyricsParsers.parseClock("27.395"))
        assertEquals(140353, LyricsParsers.parseClock("2:20.353"))
        assertEquals(3_723_456, LyricsParsers.parseClock("1:02:03.456"))
        assertEquals(123400, LyricsParsers.parseClock("123.4s"))
        assertNull(LyricsParsers.parseClock("abc"))
    }

    @Test
    fun `invalid xml or doctype is null`() {
        assertNull(LyricsParsers.parseTtml("not xml"))
        assertNull(LyricsParsers.parseBetterLyricsJson("""{"ttml":""}"""))
    }

    @Test
    fun `betterlyrics unwraps the ttml field`() {
        val json = org.json.JSONObject().put("ttml", ttml).toString()
        assertEquals(LyricsSync.WORD, LyricsParsers.parseBetterLyricsJson(json)!!.sync)
    }

    // ---- LyricsPlus ----

    @Test
    fun `lyricsplus builds segments from syllabus and trims the trailing space`() {
        val json = """{"type":"Word","lyrics":[
            {"time":38466,"duration":1793,"text":"x","syllabus":[
              {"time":38466,"duration":209,"text":"I'm "},
              {"time":38675,"duration":297,"text":"going "},
              {"time":39164,"duration":143,"text":"with"},
              {"time":39307,"duration":952,"text":"drawals "}]}]}"""
        val l = LyricsParsers.parseLyricsPlusJson(json)!!
        assertEquals(LyricsSync.WORD, l.sync)
        val line = l.lines[0]
        assertEquals("I'm going withdrawals", line.text)
        assertEquals(38466, line.startMs)
        assertEquals(40259, line.endMs)
        assertEquals(listOf(0 to 3, 4 to 9, 10 to 14, 14 to 21), line.segments.map { it.startChar to it.endChar })
        assertEquals(39307, line.segments[3].startMs)
    }

    @Test
    fun `lyricsplus line type without syllabus is line sync`() {
        val json = """{"type":"Line","lyrics":[{"time":1000,"duration":500,"text":"Hello"},{"time":2000,"duration":500,"text":"World"}]}"""
        val l = LyricsParsers.parseLyricsPlusJson(json)!!
        assertEquals(LyricsSync.LINE, l.sync)
        assertEquals(2, l.lines.size)
    }

    // ---- Genius ----

    @Test
    fun `genius html keeps lyric text, drops nested header block, section headers and entities`() {
        val html = """
            <div class="x"><div data-lyrics-container="true" class="L"><div data-exclude-from-selection="true" class="H"><div><button>12 Contributors</button><button>Embed</button></div></div></div>
            <div data-lyrics-container="true" class="L">[Chorus]<br/>I&#x27;m blinded &amp; free<br/><a href="/a"><span>second line</span></a><br/><br/>[Verse 2]<br/>third&nbsp;line</div>
            <div>Other 99Embed</div></div>
        """.trimIndent()
        val l = LyricsParsers.parseGeniusHtml(html)!!
        assertEquals(LyricsSync.PLAIN, l.sync)
        assertEquals(listOf("I'm blinded & free", "second line", "third line"), l.lines.map { it.text })
    }

    @Test
    fun `genius page without lyrics container is null`() {
        assertNull(LyricsParsers.parseGeniusHtml("<html><body>nope</body></html>"))
    }
}
