package com.mediacontrol.remote.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveLyricsTest {
    private val q = LyricsQuery("Blinding Lights", "The Weeknd", 200_000L)

    private val lrc = "[00:01.00] one\n[00:02.00] two"
    private val plusWord = """{"type":"Word","lyrics":[{"time":1000,"duration":900,"text":"hi","syllabus":[{"time":1000,"duration":900,"text":"hi"}]}]}"""
    private val plusLine = """{"type":"Line","lyrics":[{"time":1000,"duration":900,"text":"hi"}]}"""
    private val geniusSearch = """{"response":{"sections":[{"hits":[{"type":"song","result":{"title":"Blinding Lights","url":"https://genius.com/g"}}]}]}}"""
    private val geniusHtml = """<div data-lyrics-container="true">la la<br/>da da</div>"""

    /** Responds to the first registered substring contained in the URL; everything else is null. */
    private class Fake(private val routes: List<Pair<String, String>>) : HttpGet {
        val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
        override suspend fun get(url: String, headers: Map<String, String>): String? {
            urls += url
            return routes.firstOrNull { url.contains(it.first) }?.second
        }
    }

    private fun lrclib() = "lrclib.net/api/get" to org.json.JSONObject().put("syncedLyrics", lrc).toString()

    @Test
    fun `auto prefers a word-synced lyricsplus result over line-synced lrclib`() = runBlocking {
        val r = resolveLyrics(LyricsProvider.AUTO, q, Fake(listOf("binimum.org" to plusWord, lrclib())))!!
        assertEquals(LyricsSync.WORD, r.sync)
        assertEquals(LyricsProvider.LYRICS_PLUS, r.provider)
    }

    @Test
    fun `auto falls to line sync from lrclib before touching genius`() = runBlocking {
        val http = Fake(listOf(lrclib(), "genius.com/api" to geniusSearch, "genius.com/g" to geniusHtml))
        val r = resolveLyrics(LyricsProvider.AUTO, q, http)!!
        assertEquals(LyricsProvider.LRCLIB, r.provider)
        assertEquals(LyricsSync.LINE, r.sync)
        assertEquals(false, http.urls.any { it.contains("genius.com") })
    }

    @Test
    fun `auto line-synced lyricsplus beats lrclib`() = runBlocking {
        val r = resolveLyrics(LyricsProvider.AUTO, q, Fake(listOf("prjktla" to plusLine, lrclib())))!!
        assertEquals(LyricsProvider.LYRICS_PLUS, r.provider)
        assertEquals(LyricsSync.LINE, r.sync)
    }

    @Test
    fun `auto with only genius returns plain text`() = runBlocking {
        val r = resolveLyrics(LyricsProvider.AUTO, q, Fake(listOf("genius.com/api" to geniusSearch, "genius.com/g" to geniusHtml)))!!
        assertEquals(LyricsSync.PLAIN, r.sync)
        assertEquals(LyricsProvider.GENIUS, r.provider)
        assertEquals(listOf("la la", "da da"), r.lines.map { it.text })
    }

    @Test
    fun `auto with nothing returns null`() = runBlocking {
        assertNull(resolveLyrics(LyricsProvider.AUTO, q, Fake(emptyList())))
    }

    @Test
    fun `explicit provider never falls back`() = runBlocking {
        val http = Fake(listOf("lrclib.net/api/get" to org.json.JSONObject().put("syncedLyrics", lrc).toString()))
        assertNull(resolveLyrics(LyricsProvider.GENIUS, q, http))
        assertNull(resolveLyrics(LyricsProvider.LYRICS_PLUS, q, http))
        assertEquals(LyricsProvider.LRCLIB, resolveLyrics(LyricsProvider.LRCLIB, q, http)!!.provider)
    }

    @Test
    fun `lrc-red skips remixes and mismatched durations`() = runBlocking {
        val search = """{"hits":[
            {"isrc":"R1","title":"Blinding Lights (Remix)","duration":200.0},
            {"isrc":"R2","title":"Blinding Lights","duration":230.0},
            {"isrc":"R3","title":"Blinding Lights","duration":200.5}]}"""
        val ttml = """<tt><body><div><p begin="1.0" end="2.0"><span begin="1.0" end="2.0">hey</span></p></div></body></tt>"""
        val http = Fake(listOf("search.json" to search, "/s/R3.ttml" to ttml))
        val r = resolveLyrics(LyricsProvider.LRC_RED, q, http)!!
        assertEquals(LyricsSync.WORD, r.sync)
        assertEquals(true, http.urls.any { it.endsWith("/s/R3.ttml") })
    }

    @Test
    fun `kugou walks search, candidate and base64 download`() = runBlocking {
        val content = java.util.Base64.getEncoder().encodeToString("[00:01.00]hello\n[00:02.00]Lyrics by：X".toByteArray())
        val http = Fake(
            listOf(
                "mobileservice.kugou.com" to """{"data":{"info":[{"hash":"H0","songname":"Blinding Lights (Live)","duration":200},{"hash":"H1","songname":"Blinding Lights","duration":201}]}}""",
                "lyrics.kugou.com/search" to """{"candidates":[{"id":"7","accesskey":"K","duration":200097}]}""",
                "lyrics.kugou.com/download" to """{"content":"$content"}""",
            ),
        )
        val r = resolveLyrics(LyricsProvider.KUGOU, q, http)!!
        assertEquals(listOf("hello"), r.lines.map { it.text })
        assertEquals(true, http.urls.any { it.contains("hash=H1") })
    }

    @Test
    fun `search cleanup removes feat and video noise but keeps remix`() {
        assertEquals("Song", "Song (feat. Someone)".forLyricsSearch())
        assertEquals("Song", "Song [ft. X] (Official Music Video)".forLyricsSearch())
        assertEquals("Song (Remix)", "Song (Remix) (Lyric Video)".forLyricsSearch())
        assertEquals("Artist", "Artist - Topic".forLyricsSearch())
    }
}
