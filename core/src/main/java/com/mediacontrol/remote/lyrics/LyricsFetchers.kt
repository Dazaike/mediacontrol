package com.mediacontrol.remote.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToLong

/** Minimal HTTP seam so provider selection is testable. Null on non-200, IO error, or timeout. */
fun interface HttpGet {
    suspend fun get(url: String, headers: Map<String, String>): String?
}

suspend fun HttpGet.get(url: String): String? = get(url, emptyMap())

private val FEAT = Regex("(?i)\\s*[(\\[]\\s*(?:feat|ft|featuring)\\b[^)\\]]*[)\\]]")
private val NOISE = Regex(
    "(?i)\\s*[(\\[]\\s*(?:official[^)\\]]*|lyrics?(?:\\s+video)?|audio|hd|4k)\\s*[)\\]]",
)
private val WS = Regex("\\s+")
private val VERSION_WORDS = Regex("(?i)\\b(live|remix|acoustic|karaoke|sped|slowed|instrumental)\\b")

/** Strips decorations that hurt provider matching; keeps Remix/Live/Acoustic/Remastered. */
fun String.forLyricsSearch(): String =
    removeSuffix(" - Topic").let { NOISE.replace(FEAT.replace(it, ""), "") }
        .replace(WS, " ").trim()

private fun String.normalized(): String = lowercase().filter { it.isLetterOrDigit() }

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private fun seconds(ms: Long): Long = (ms / 1000.0).roundToLong()

/** True when candidate's duration (sec) is within [tolSec] of the query's, or the query has none. */
private fun durationClose(candidateSec: Double, q: LyricsQuery, tolSec: Double): Boolean =
    q.durationMs <= 0 || abs(candidateSec - q.durationMs / 1000.0) <= tolSec

/** A candidate with live/remix/etc. in its title only matches when the query asks for it. */
private fun versionOk(candidateTitle: String, queryTitle: String): Boolean =
    !VERSION_WORDS.containsMatchIn(candidateTitle) || VERSION_WORDS.containsMatchIn(queryTitle)

private const val FETCH_TIMEOUT_MS = 15_000L

private val LYRICS_PLUS_MIRRORS = listOf(
    "https://lyricsplus.prjktla.my.id",
    "https://lyricsplus.atomix.one",
    "https://lyricsplus.binimum.org",
)

suspend fun fetchLyrics(provider: LyricsProvider, q: LyricsQuery, http: HttpGet): Lyrics? {
    if (provider == LyricsProvider.AUTO) return resolveLyrics(provider, q, http)
    if (q.title.isBlank()) return null
    val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
        try {
            when (provider) {
                LyricsProvider.LRCLIB -> fetchLrclib(q, http)
                LyricsProvider.KUGOU -> fetchKugou(q, http)
                LyricsProvider.LRC_RED -> fetchLrcRed(q, http)
                LyricsProvider.BETTER_LYRICS -> fetchBetterLyrics(q, http)
                LyricsProvider.LYRICS_PLUS -> fetchLyricsPlus(q, http)
                LyricsProvider.GENIUS -> fetchGenius(q, http)
                LyricsProvider.AUTO -> null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    return result?.copy(provider = provider)
}

private suspend fun fetchLrclib(q: LyricsQuery, http: HttpGet): Lyrics? {
    val base = "track_name=${enc(q.title)}&artist_name=${enc(q.artist)}"
    val sec = seconds(q.durationMs)
    val direct = http.get("https://lrclib.net/api/get?$base&duration=$sec")
        ?.let(LyricsParsers::parseLrclibJson)
    if (direct != null) return direct
    val arr = org.json.JSONArray(http.get("https://lrclib.net/api/search?$base") ?: return null)
    var best: JSONObject? = null
    var bestDelta = Double.MAX_VALUE
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("syncedLyrics").isBlank()) continue
        val delta = abs(o.optDouble("duration") - q.durationMs / 1000.0)
        if (q.durationMs > 0 && delta > 5) continue
        if (delta < bestDelta) {
            best = o
            bestDelta = delta
        }
    }
    return best?.optString("syncedLyrics")?.let(LyricsParsers::parseLrc)
}

private suspend fun fetchKugou(q: LyricsQuery, http: HttpGet): Lyrics? {
    val keyword = enc("${q.artist} - ${q.title}")
    val search = JSONObject(
        http.get("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$keyword")
            ?: return null,
    )
    val infos = search.optJSONObject("data")?.optJSONArray("info") ?: return null
    var hash: String? = null
    for (i in 0 until infos.length()) {
        val o = infos.optJSONObject(i) ?: continue
        if (!durationClose(o.optDouble("duration"), q, 8.0)) continue
        if (!versionOk(o.optString("songname"), q.title)) continue
        hash = o.optString("hash").takeIf { it.isNotEmpty() } ?: continue
        break
    }
    hash ?: return null
    val candidates = JSONObject(
        http.get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=$hash") ?: return null,
    ).optJSONArray("candidates") ?: return null
    if (candidates.length() == 0) return null
    var pick: JSONObject? = null
    for (i in 0 until candidates.length()) {
        val c = candidates.optJSONObject(i) ?: continue
        if (durationClose(c.optDouble("duration") / 1000.0, q, 8.0)) {
            pick = c
            break
        }
    }
    pick ?: return null
    val id = pick.optString("id")
    val key = pick.optString("accesskey")
    val dl = JSONObject(
        http.get("https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=${enc(id)}&accesskey=${enc(key)}")
            ?: return null,
    )
    val content = dl.optString("content").takeIf { it.isNotEmpty() } ?: return null
    val lrc = String(Base64.getMimeDecoder().decode(content), Charsets.UTF_8)
    return LyricsParsers.parseKugouLrc(lrc)
}

private suspend fun fetchLrcRed(q: LyricsQuery, http: HttpGet): Lyrics? {
    val hits = JSONObject(
        http.get("https://lrc.red/search.json?q=${enc("${q.title} ${q.artist}")}") ?: return null,
    ).optJSONArray("hits") ?: return null
    val want = q.title.normalized()
    for (i in 0 until hits.length()) {
        val h = hits.optJSONObject(i) ?: continue
        val title = h.optString("title")
        if (title.normalized() != want) continue
        if (!durationClose(h.optDouble("duration"), q, 3.0)) continue
        if (!versionOk(title, q.title)) continue
        val isrc = h.optString("isrc").takeIf { it.isNotEmpty() } ?: continue
        val ttml = http.get("https://lrc.red/s/${enc(isrc)}.ttml") ?: return null
        return LyricsParsers.parseTtml(ttml)
    }
    return null
}

private suspend fun fetchBetterLyrics(q: LyricsQuery, http: HttpGet): Lyrics? =
    http.get(
        "https://lyrics-api.boidu.dev/getLyrics?s=${enc(q.title)}&a=${enc(q.artist)}&d=${seconds(q.durationMs)}",
    )?.let(LyricsParsers::parseBetterLyricsJson)

/** Mirrors queried concurrently; the first (in list order) word-synced result wins, else the first line-synced. */
private suspend fun fetchLyricsPlus(q: LyricsQuery, http: HttpGet): Lyrics? = coroutineScope {
    val path = "/v2/lyrics/get?title=${enc(q.title)}&artist=${enc(q.artist)}&duration=${seconds(q.durationMs)}"
    val results = LYRICS_PLUS_MIRRORS.map { mirror ->
        async {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                http.get(mirror + path)?.let(LyricsParsers::parseLyricsPlusJson)
            }
        }
    }.map { it.await() }
    results.firstOrNull { it?.sync == LyricsSync.WORD } ?: results.firstOrNull { it != null }
}

private val GENIUS_HEADERS = mapOf("User-Agent" to "BitChord")

private suspend fun fetchGenius(q: LyricsQuery, http: HttpGet): Lyrics? {
    val sections = JSONObject(
        http.get("https://genius.com/api/search/multi?q=${enc("${q.artist} ${q.title}")}", GENIUS_HEADERS)
            ?: return null,
    ).optJSONObject("response")?.optJSONArray("sections") ?: return null
    val want = q.title.normalized()
    for (s in 0 until sections.length()) {
        val hits = sections.optJSONObject(s)?.optJSONArray("hits") ?: continue
        for (i in 0 until hits.length()) {
            val hit = hits.optJSONObject(i) ?: continue
            if (hit.optString("type") != "song") continue
            val result = hit.optJSONObject("result") ?: continue
            if (!result.optString("title").normalized().contains(want)) continue
            val url = result.optString("url").takeIf { it.isNotEmpty() } ?: continue
            return http.get(url, GENIUS_HEADERS)?.let(LyricsParsers::parseGeniusHtml)
        }
    }
    return null
}

/**
 * Explicit provider: that provider only, no fallback. [LyricsProvider.AUTO]: word-synced
 * (LRC.red, BetterLyrics, LyricsPlus) → line-synced (those, then LRCLIB, KuGou) → Genius plain.
 */
suspend fun resolveLyrics(requested: LyricsProvider, q: LyricsQuery, http: HttpGet): Lyrics? {
    if (requested != LyricsProvider.AUTO) return fetchLyrics(requested, q, http)
    if (q.title.isBlank()) return null
    return coroutineScope {
        val tier1 = listOf(LyricsProvider.LRC_RED, LyricsProvider.BETTER_LYRICS, LyricsProvider.LYRICS_PLUS)
            .map { p -> async { fetchLyrics(p, q, http) } }
            .map { it.await() }
        tier1.firstOrNull { it?.sync == LyricsSync.WORD }?.let { return@coroutineScope it }

        val tier2 = listOf(LyricsProvider.LRCLIB, LyricsProvider.KUGOU)
            .map { p -> async { fetchLyrics(p, q, http) } }
            .map { it.await() }
        (tier1 + tier2).firstOrNull { it != null && it.sync != LyricsSync.PLAIN }
            ?: fetchLyrics(LyricsProvider.GENIUS, q, http)
    }
}
