package com.mediacontrol.remote.soundcore

/**
 * Soundcore RFCOMM wire format, pure bytes so it stays unit-testable off-device.
 *
 * Frame: `[5-byte direction][2-byte command][2-byte total length, little-endian][body][1-byte checksum]`,
 * checksum = wrapping 8-bit sum of every preceding byte.
 */
private val DIRECTION_OUT = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00)
private val DIRECTION_IN = byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01)

/** Smallest legal frame: direction + command + length + checksum. */
private const val HEADER_AND_CHECKSUM = 10

enum class SoundcoreMode(val value: Byte, val label: String) {
    NOISE_CANCELING(0x00, "Noise Canceling"),
    TRANSPARENCY(0x01, "Transparency"),
    NORMAL(0x02, "Normal"),
}

val CMD_STATE = byteArrayOf(0x01, 0x01)
val CMD_SET_SOUND_MODES = byteArrayOf(0x06, 0x81.toByte())
val CMD_SOUND_MODE_UPDATE = byteArrayOf(0x06, 0x01)

private fun checksum(bytes: ByteArray, length: Int): Byte {
    var sum = 0
    for (i in 0 until length) sum += bytes[i].toInt() and 0xFF
    return (sum and 0xFF).toByte()
}

fun buildPacket(command: ByteArray, body: ByteArray): ByteArray {
    val total = HEADER_AND_CHECKSUM + body.size
    val out = ByteArray(total)
    DIRECTION_OUT.copyInto(out, 0)
    command.copyInto(out, 5)
    out[7] = (total and 0xFF).toByte()
    out[8] = ((total shr 8) and 0xFF).toByte()
    body.copyInto(out, 9)
    out[total - 1] = checksum(out, total - 1)
    return out
}

fun requestStatePacket(): ByteArray = buildPacket(CMD_STATE, ByteArray(0))

data class SoundcoreFrame(val command: ByteArray, val body: ByteArray) {
    fun isCommand(other: ByteArray): Boolean = command.contentEquals(other)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoundcoreFrame) return false
        return command.contentEquals(other.command) && body.contentEquals(other.body)
    }

    override fun hashCode(): Int = 31 * command.contentHashCode() + body.contentHashCode()
}

/** Stateful de-framer: RFCOMM delivers partial frames and several frames per read. */
class SoundcoreFrameReader {

    private var buffer = ByteArray(0)

    fun append(chunk: ByteArray, length: Int): List<SoundcoreFrame> {
        if (length > 0) {
            val grown = ByteArray(buffer.size + length)
            buffer.copyInto(grown, 0)
            chunk.copyInto(grown, buffer.size, 0, length)
            buffer = grown
        }
        val frames = ArrayList<SoundcoreFrame>()
        var offset = 0
        while (buffer.size - offset >= HEADER_AND_CHECKSUM) {
            if (!matchesInbound(offset)) {
                offset++
                continue
            }
            val frameLength = (buffer[offset + 7].toInt() and 0xFF) or
                ((buffer[offset + 8].toInt() and 0xFF) shl 8)
            if (frameLength < HEADER_AND_CHECKSUM) {
                offset++
                continue
            }
            // Incomplete tail: keep everything from here for the next read.
            if (buffer.size - offset < frameLength) break
            val expected = checksumAt(offset, frameLength - 1)
            if (expected != buffer[offset + frameLength - 1]) {
                offset++
                continue
            }
            frames += SoundcoreFrame(
                command = buffer.copyOfRange(offset + 5, offset + 7),
                body = buffer.copyOfRange(offset + 9, offset + frameLength - 1),
            )
            offset += frameLength
        }
        if (offset > 0) buffer = buffer.copyOfRange(offset, buffer.size)
        return frames
    }

    private fun matchesInbound(offset: Int): Boolean {
        for (i in DIRECTION_IN.indices) {
            if (buffer[offset + i] != DIRECTION_IN[i]) return false
        }
        return true
    }

    private fun checksumAt(offset: Int, length: Int): Byte {
        var sum = 0
        for (i in 0 until length) sum += buffer[offset + i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }
}

data class SoundcoreProfile(
    val label: String,
    val soundModesOffset: Int,
    val soundModesLength: Int,
    val mirrorsModeAtIndex2: Boolean,
) {
    /** Sound-mode bytes carved out of a [CMD_STATE] response body, or null if it is too short. */
    fun extract(stateBody: ByteArray): ByteArray? {
        if (stateBody.size < soundModesOffset + soundModesLength) return null
        return stateBody.copyOfRange(soundModesOffset, soundModesOffset + soundModesLength)
    }

    /** Copy with byte 0 (and byte 2 when [mirrorsModeAtIndex2]) set to [mode]; all other bytes preserved. */
    fun patched(soundModes: ByteArray, mode: SoundcoreMode): ByteArray {
        val out = soundModes.copyOf()
        if (out.isEmpty()) return out
        out[0] = mode.value
        if (mirrorsModeAtIndex2 && out.size > 2) out[2] = mode.value
        return out
    }
}

object SoundcoreProfiles {
    // Soundcore Space One (A3035): 92-byte state body, 6 sound-mode bytes at 69;
    // byte 2 repeats the ambient mode on this model.
    val SPACE_ONE = SoundcoreProfile("Space One", soundModesOffset = 69, soundModesLength = 6, mirrorsModeAtIndex2 = true)

    // Soundcore P31i (D1202): 139-byte state body, 8 sound-mode bytes at 119.
    val P31I = SoundcoreProfile("P31i", soundModesOffset = 119, soundModesLength = 8, mirrorsModeAtIndex2 = false)

    fun isSoundcore(deviceName: String?): Boolean =
        deviceName?.lowercase()?.contains("soundcore") == true

    /** Null when the model is unknown — caller falls back to [legacyV1Body]. */
    fun forName(deviceName: String?): SoundcoreProfile? {
        val n = deviceName?.lowercase() ?: return null
        return when {
            n.contains("space one") -> SPACE_ONE
            n.contains("p31i") -> P31I
            else -> null
        }
    }
}

/** 4-byte body understood by older models: [mode, ancMode=Transport, transparency=VocalMode, customAnc=0]. */
fun legacyV1Body(mode: SoundcoreMode): ByteArray = byteArrayOf(mode.value, 0x00, 0x01, 0x00)

/** Last mode the earbuds confirmed, plus the last failure reason ("" when fine). */
data class SoundcoreStatus(val mode: SoundcoreMode?, val error: String)
