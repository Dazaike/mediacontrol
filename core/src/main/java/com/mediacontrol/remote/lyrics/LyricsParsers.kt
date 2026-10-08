package com.mediacontrol.remote.lyrics

import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Pure parsers for each provider's response shape. Each returns null for unusable or empty
 * input. The `provider` on results is a placeholder; callers `copy(provider = ...)`.
 */
object LyricsParsers {
    private val PLACEHOLDER = LyricsProvider.LRCLIB
    private const val LAST_LINE_MS = 5000
    private const val WORD_MIN_RATIO = 0.5

    private val LRC_STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{2,3}))?]")
    private val LRC_WORD_TAG = Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")
    private val KUGOU_CREDIT = Regex(
        "(?i)^(作词|作曲|编曲|词|曲|lyrics by|composed by|composer|arranged by|arranger|produced by|producer|written by)\\s*[:：]",
    )

    // ---- LRC ----------------------------------------------------------------------------

    fun parseLrc(text: String): Lyrics? {
        val starts = ArrayList<Pair<Int, String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            val m = LRC_STAMP.matcher(line)
            var pos = 0
            val stamps = ArrayList<Int>()
            while (pos < line.length) {
                m.region(pos, line.length)
                if (!m.lookingAt()) break
                val frac = m.group(3)
                val fracMs = when (frac?.length) {
                    2 -> frac.toInt() * 10
                    3 -> frac.toInt()
                    else -> 0
                }
                stamps += (m.group(1).toInt() * 60 + m.group(2).toInt()) * 1000 + fracMs
                pos = m.end()
            }
            if (stamps.isEmpty()) continue
            val body = LRC_WORD_TAG.replace(line.substring(pos), "").trim()
            if (body.isEmpty()) continue
            for (s in stamps) starts += s to body
        }
        if (starts.isEmpty()) return null
        val sorted = starts.sortedBy { it.first }
        val lines = sorted.mapIndexed { i, (start, body) ->
            val end = if (i + 1 < sorted.size) sorted[i + 1].first else start + LAST_LINE_MS
            LyricLine(start, end, body)
        }
        return Lyrics(LyricsSync.LINE, lines, PLACEHOLDER)
    }

    fun parseLrclibJson(json: String): Lyrics? = try {
        val synced = JSONObject(json).optString("syncedLyrics")
        if (synced.isNullOrBlank()) null else parseLrc(synced)
    } catch (e: Exception) {
        null
    }

    fun parseKugouLrc(text: String): Lyrics? {
        val parsed = parseLrc(text) ?: return null
        val lines = parsed.lines.filterNot {
            KUGOU_CREDIT.containsMatchIn(it.text) || (it.startMs == 0 && it.text.contains(" - "))
        }
        return if (lines.isEmpty()) null else parsed.copy(lines = lines)
    }

    // ---- TTML ---------------------------------------------------------------------------

    private val SKIPPED_ROLES = setOf("x-bg", "x-translation", "x-roman")

    /** `ss.mmm`, `m:ss.mmm`, `h:mm:ss.mmm`, or `123.4s` to milliseconds; null if malformed. */
    fun parseClock(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            if (t.endsWith("s")) return (t.dropLast(1).toDouble() * 1000).toInt()
            val parts = t.split(':')
            if (parts.size > 3) return null
            var seconds = 0.0
            for (p in parts) seconds = seconds * 60 + p.toDouble()
            (seconds * 1000).toInt()
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun parseTtml(xml: String): Lyrics? {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            return null
        }
        val ps = doc.getElementsByTagName("p")
        val lines = ArrayList<LyricLine>()
        for (i in 0 until ps.length) {
            val p = ps.item(i) as? Element ?: continue
            buildTtmlLine(p)?.let { lines += it }
        }
        if (lines.isEmpty()) return null
        lines.sortBy { it.startMs }
        return finishTimed(lines)
    }

    private class LineBuilder {
        val text = StringBuilder()
        val segments = ArrayList<LyricSegment>()
        var pendingSpace = false
    }

    private fun buildTtmlLine(p: Element): LyricLine? {
        val start = p.getAttribute("begin").takeIf { it.isNotEmpty() }?.let(::parseClock)
        val end = p.getAttribute("end").takeIf { it.isNotEmpty() }?.let(::parseClock)
        val b = LineBuilder()
        walkTtml(p, b)
        val text = b.text.toString()
        if (text.isBlank()) return null
        val segStart = b.segments.firstOrNull()?.startMs
        val segEnd = b.segments.lastOrNull()?.endMs
        val s = start ?: segStart ?: return null
        val e = end ?: segEnd ?: (s + LAST_LINE_MS)
        return LyricLine(s, e, text, b.segments)
    }

    private fun walkTtml(parent: Node, b: LineBuilder) {
        var child = parent.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> appendUntimed(child.nodeValue.orEmpty(), b)
                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    val role = el.getAttribute("ttm:role")
                    if (role in SKIPPED_ROLES) {
                        // skipped runs still separate words
                        b.pendingSpace = true
                    } else if (el.tagName == "br") {
                        b.pendingSpace = true
                    } else {
                        val s = el.getAttribute("begin").takeIf { it.isNotEmpty() }?.let(::parseClock)
                        val e = el.getAttribute("end").takeIf { it.isNotEmpty() }?.let(::parseClock)
                        if (s != null && e != null && !hasElementChildren(el)) {
                            appendTimed(el.textContent.orEmpty(), s, e, b)
                        } else {
                            walkTtml(el, b)
                        }
                    }
                }
            }
            child = child.nextSibling
        }
    }

    private fun hasElementChildren(el: Element): Boolean {
        var c = el.firstChild
        while (c != null) {
            if (c.nodeType == Node.ELEMENT_NODE) return true
            c = c.nextSibling
        }
        return false
    }

    private fun appendUntimed(raw: String, b: LineBuilder) {
        if (raw.isEmpty()) return
        if (raw.isBlank()) {
            b.pendingSpace = true
            return
        }
        if (raw.first().isWhitespace()) b.pendingSpace = true
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        if (b.pendingSpace && b.text.isNotEmpty()) b.text.append(' ')
        b.text.append(trimmed)
        b.pendingSpace = raw.last().isWhitespace()
    }

    private fun appendTimed(raw: String, startMs: Int, endMs: Int, b: LineBuilder) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            b.pendingSpace = true
            return
        }
        if (raw.first().isWhitespace()) b.pendingSpace = true
        if (b.pendingSpace && b.text.isNotEmpty()) b.text.append(' ')
        val sc = b.text.length
        b.text.append(trimmed)
        b.segments += LyricSegment(startMs, endMs, sc, b.text.length)
        b.pendingSpace = raw.last().isWhitespace()
    }

    /** Picks WORD vs LINE for a set of timed lines by the share carrying segments. */
    private fun finishTimed(lines: List<LyricLine>): Lyrics {
        val withSegments = lines.count { it.segments.isNotEmpty() }
        return if (withSegments >= lines.size * WORD_MIN_RATIO) {
            Lyrics(LyricsSync.WORD, lines, PLACEHOLDER)
        } else {
            Lyrics(LyricsSync.LINE, lines.map { it.copy(segments = emptyList()) }, PLACEHOLDER)
        }
    }

    fun parseBetterLyricsJson(json: String): Lyrics? = try {
        val ttml = JSONObject(json).optString("ttml")
        if (ttml.isNullOrBlank()) null else parseTtml(ttml)
    } catch (e: Exception) {
        null
    }

    // ---- LyricsPlus ---------------------------------------------------------------------

    fun parseLyricsPlusJson(json: String): Lyrics? = try {
        val items = JSONObject(json).optJSONArray("lyrics")
        val lines = ArrayList<LyricLine>()
        if (items != null) {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val start = o.optLong("time").toInt()
                val end = start + o.optLong("duration").toInt()
                val syllabus = o.optJSONArray("syllabus")
                if (syllabus != null && syllabus.length() > 0) {
                    buildSyllabusLine(syllabus, start, end)?.let { lines += it }
                } else {
                    val text = o.optString("text").trim()
                    if (text.isNotEmpty()) lines += LyricLine(start, end, text)
                }
            }
        }
        if (lines.isEmpty()) null else finishTimed(lines.sortedBy { it.startMs })
    } catch (e: Exception) {
        null
    }

    private fun buildSyllabusLine(syllabus: org.json.JSONArray, start: Int, end: Int): LyricLine? {
        val raw = StringBuilder()
        val spans = ArrayList<IntArray>() // startMs, endMs, startChar, endChar (pre-trim)
        for (j in 0 until syllabus.length()) {
            val s = syllabus.optJSONObject(j) ?: continue
            val t = s.optString("text")
            if (t.isEmpty()) continue
            val sc = raw.length
            raw.append(t)
            if (t.isBlank()) continue
            val st = s.optLong("time").toInt()
            spans += intArrayOf(
                st,
                st + s.optLong("duration").toInt(),
                sc + t.indexOfFirst { !it.isWhitespace() },
                sc + t.indexOfLast { !it.isWhitespace() } + 1,
            )
        }
        val lead = raw.indexOfFirst { !it.isWhitespace() }
        if (lead < 0) return null
        val text = raw.toString().trim()
        val segments = spans.mapNotNull {
            val a = (it[2] - lead).coerceAtLeast(0)
            val z = (it[3] - lead).coerceAtMost(text.length)
            if (z > a) LyricSegment(it[0], it[1], a, z) else null
        }
        return LyricLine(start, end, text, segments)
    }

    // ---- Genius -------------------------------------------------------------------------

    private val DIV_TAG = Regex("<(/?)div\\b[^>]*>")
    private val LYRICS_CONTAINER_OPEN = Regex("<div[^>]*data-lyrics-container=\"true\"[^>]*>")
    private val BR = Regex("<br\\s*/?>")
    private val ANY_TAG = Regex("<[^>]*>")
    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")
    private val EMBED_LINE = Regex("^\\d*Embed$")
    private val SECTION_HEADER = Regex("^\\[[^\\]]*]$")

    fun parseGeniusHtml(html: String): Lyrics? {
        val sb = StringBuilder()
        for (open in LYRICS_CONTAINER_OPEN.findAll(html)) {
            sb.append(containerInner(html, open.range.last + 1)).append('\n')
        }
        if (sb.isBlank()) return null
        val text = unescapeHtml(ANY_TAG.replace(BR.replace(sb.toString(), "\n"), ""))
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { EMBED_LINE.matches(it) || it == "You might also like" || SECTION_HEADER.matches(it) }
            .map { LyricLine(0, 0, it) }
            .toList()
        return if (lines.isEmpty()) null else Lyrics(LyricsSync.PLAIN, lines, LyricsProvider.GENIUS)
    }

    /** Inner HTML from [from] to the matching `</div>`, minus `data-exclude-from-selection` sub-blocks. */
    private fun containerInner(html: String, from: Int): String {
        val out = StringBuilder()
        var depth = 1
        var skipDepth = -1 // depth at which a skipped block was opened, -1 = not skipping
        var last = from
        for (t in DIV_TAG.findAll(html, from)) {
            val closing = t.groupValues[1].isNotEmpty()
            if (!closing) {
                if (skipDepth < 0 && t.value.contains("data-exclude-from-selection=\"true\"")) {
                    out.append(html, last, t.range.first)
                    skipDepth = depth
                }
                depth++
            } else {
                depth--
                if (skipDepth >= 0 && depth == skipDepth) {
                    skipDepth = -1
                    last = t.range.last + 1
                } else if (depth == 0) {
                    if (skipDepth < 0) out.append(html, last, t.range.first)
                    break
                }
            }
        }
        return out.toString()
    }

    private fun unescapeHtml(s: String): String {
        val numeric = NUMERIC_ENTITY.replace(s) { m ->
            val cp = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        return numeric
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
    }
}
