package com.mediacontrol.remote.soundcore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundcoreProtocolTest {

    private fun wrappingSum(bytes: ByteArray, length: Int): Byte {
        var sum = 0
        for (i in 0 until length) sum += bytes[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    /** Builds a well-formed inbound frame the way the earbuds would emit it. */
    private fun inboundFrame(command: ByteArray, body: ByteArray): ByteArray {
        val total = 10 + body.size
        val out = ByteArray(total)
        byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01).copyInto(out, 0)
        command.copyInto(out, 5)
        out[7] = (total and 0xFF).toByte()
        out[8] = ((total shr 8) and 0xFF).toByte()
        body.copyInto(out, 9)
        out[total - 1] = wrappingSum(out, total - 1)
        return out
    }

    @Test
    fun `state request is the known ten-byte packet`() {
        assertArrayEquals(
            byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00, 0x01, 0x01, 0x0A, 0x00, 0x02),
            requestStatePacket(),
        )
    }

    @Test
    fun `set sound modes packet carries its length and checksum`() {
        val packet = buildPacket(CMD_SET_SOUND_MODES, byteArrayOf(0x01, 0x00, 0x01, 0x00))
        assertEquals(14, packet.size)
        assertEquals(0x0E.toByte(), packet[7])
        assertEquals(0x00.toByte(), packet[8])
        assertEquals(wrappingSum(packet, 13), packet[13])
    }

    @Test
    fun `reader returns both frames arriving in one chunk`() {
        val a = inboundFrame(CMD_STATE, ByteArray(4) { 0x11 })
        val b = inboundFrame(CMD_SOUND_MODE_UPDATE, byteArrayOf(0x01, 0x02))
        val chunk = a + b
        val frames = SoundcoreFrameReader().append(chunk, chunk.size)
        assertEquals(2, frames.size)
        assertTrue(frames[0].isCommand(CMD_STATE))
        assertArrayEquals(ByteArray(4) { 0x11 }, frames[0].body)
        assertTrue(frames[1].isCommand(CMD_SOUND_MODE_UPDATE))
        assertArrayEquals(byteArrayOf(0x01, 0x02), frames[1].body)
    }

    @Test
    fun `reader waits for the tail of a split frame`() {
        val frame = inboundFrame(CMD_STATE, ByteArray(8) { it.toByte() })
        val reader = SoundcoreFrameReader()
        val head = frame.copyOfRange(0, 11)
        assertTrue(reader.append(head, head.size).isEmpty())
        val tail = frame.copyOfRange(11, frame.size)
        val frames = reader.append(tail, tail.size)
        assertEquals(1, frames.size)
        assertArrayEquals(ByteArray(8) { it.toByte() }, frames[0].body)
    }

    @Test
    fun `reader drops a corrupted frame and still parses the next one`() {
        val bad = inboundFrame(CMD_STATE, ByteArray(4) { 0x22 })
        bad[bad.size - 1] = (bad[bad.size - 1] + 1).toByte()
        val good = inboundFrame(CMD_SOUND_MODE_UPDATE, byteArrayOf(0x00))
        val chunk = bad + good
        val frames = SoundcoreFrameReader().append(chunk, chunk.size)
        assertEquals(1, frames.size)
        assertTrue(frames[0].isCommand(CMD_SOUND_MODE_UPDATE))
        assertArrayEquals(byteArrayOf(0x00), frames[0].body)
    }

    @Test
    fun `space one patch rewrites both mode bytes and preserves the rest`() {
        val patched = SoundcoreProfiles.SPACE_ONE.patched(
            byteArrayOf(0x02, 0x35, 0x02, 0x01, 0x01, 0x03),
            SoundcoreMode.TRANSPARENCY,
        )
        assertArrayEquals(byteArrayOf(0x01, 0x35, 0x01, 0x01, 0x01, 0x03), patched)
    }

    @Test
    fun `p31i patch rewrites only the first byte`() {
        val original = byteArrayOf(0x02, 0x11, 0x02, 0x33, 0x44, 0x55, 0x66, 0x77)
        val patched = SoundcoreProfiles.P31I.patched(original, SoundcoreMode.NOISE_CANCELING)
        assertArrayEquals(byteArrayOf(0x00, 0x11, 0x02, 0x33, 0x44, 0x55, 0x66, 0x77), patched)
    }

    @Test
    fun `extract returns null when the state body is too short`() {
        assertEquals(null, SoundcoreProfiles.SPACE_ONE.extract(ByteArray(40)))
        val body = ByteArray(92) { it.toByte() }
        assertArrayEquals(body.copyOfRange(69, 75), SoundcoreProfiles.SPACE_ONE.extract(body))
    }
}
