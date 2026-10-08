package com.mediacontrol.remote.lyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Compact binary form of [Lyrics] for a Data Layer Asset (bypasses the 100 KB DataItem
 * limit). Layout inside gzip: version, sync ordinal, provider ordinal, line count, then
 * per line start/end/text/segment count and per segment four ints.
 */
object LyricsCodec {
    private const val VERSION = 1
    const val MAX_LINES = 400
    const val MAX_LINE_CHARS = 500

    fun encode(l: Lyrics): ByteArray {
        val lines = l.lines.take(MAX_LINES).map { it.clamped() }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeByte(VERSION)
            out.writeByte(l.sync.ordinal)
            out.writeByte(l.provider.ordinal)
            out.writeInt(lines.size)
            for (line in lines) {
                out.writeInt(line.startMs)
                out.writeInt(line.endMs)
                out.writeUTF(line.text)
                out.writeShort(line.segments.size)
                for (s in line.segments) {
                    out.writeInt(s.startMs)
                    out.writeInt(s.endMs)
                    out.writeInt(s.startChar)
                    out.writeInt(s.endChar)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(b: ByteArray): Lyrics? = try {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(b))).use { input ->
            if (input.readUnsignedByte() != VERSION) return null
            val sync = LyricsSync.entries[input.readUnsignedByte()]
            val provider = LyricsProvider.entries[input.readUnsignedByte()]
            val count = input.readInt()
            if (count < 0 || count > MAX_LINES) return null
            val lines = ArrayList<LyricLine>(count)
            repeat(count) {
                val start = input.readInt()
                val end = input.readInt()
                val text = input.readUTF()
                val segCount = input.readUnsignedShort()
                val segs = ArrayList<LyricSegment>(segCount)
                repeat(segCount) {
                    segs += LyricSegment(input.readInt(), input.readInt(), input.readInt(), input.readInt())
                }
                lines += LyricLine(start, end, text, segs)
            }
            Lyrics(sync, lines, provider)
        }
    } catch (e: Exception) {
        null
    }

    private fun LyricLine.clamped(): LyricLine {
        if (text.length <= MAX_LINE_CHARS) return this
        return copy(
            text = text.substring(0, MAX_LINE_CHARS),
            segments = segments.filter { it.endChar <= MAX_LINE_CHARS },
        )
    }
}
