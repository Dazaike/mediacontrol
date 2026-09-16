package com.mediacontrol.remote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BtBannerTest {

    private val banner = "Bluetooth disconnected" to "Playback paused"

    @Test
    fun `disconnect while playing raises the exact banner`() {
        assertEquals(
            banner,
            nextBanner(prevConnected = true, connected = false, isPlaying = true, current = null),
        )
    }

    @Test
    fun `disconnect while paused raises nothing`() {
        assertNull(
            nextBanner(prevConnected = true, connected = false, isPlaying = false, current = null),
        )
    }

    @Test
    fun `steady disconnected while playing is not a new transition`() {
        assertNull(
            nextBanner(prevConnected = false, connected = false, isPlaying = true, current = null),
        )
    }

    @Test
    fun `reconnect clears an active banner`() {
        assertNull(
            nextBanner(prevConnected = false, connected = true, isPlaying = true, current = banner),
        )
    }

    @Test
    fun `steady connected keeps no banner`() {
        assertNull(
            nextBanner(prevConnected = true, connected = true, isPlaying = true, current = null),
        )
    }

    @Test
    fun `connected line shows device name`() {
        assertEquals(
            "🎧 Pixel Buds",
            btStatusLine(BtAudioState(deviceName = "Pixel Buds", connected = true, profile = "A2DP")),
        )
    }

    @Test
    fun `disconnected line is empty`() {
        assertEquals(
            "",
            btStatusLine(BtAudioState(deviceName = null, connected = false, profile = null)),
        )
    }
}
