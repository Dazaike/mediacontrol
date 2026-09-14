package com.mediacontrol.remote.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPublishTest {

    private fun sess(
        title: String = "Track",
        playing: Boolean = true,
        positionMs: Long = 1_000L,
        durationMs: Long = 180_000L,
        art: ByteArray? = null,
    ) = ControlledSession(
        packageName = "player.app",
        appLabel = "Player",
        title = title,
        artist = "Artist",
        artworkUri = null,
        isPlaying = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        artworkBytes = art,
    )

    @Test
    fun `position tick matching elapsed time is not published`() {
        val prev = sess(positionMs = 5_000L)
        val next = sess(positionMs = 6_000L)
        assertFalse(shouldPublishSession(prev, next, elapsedSincePublishMs = 1_000L))
    }

    @Test
    fun `seek jumps are published`() {
        val prev = sess(positionMs = 5_000L)
        val next = sess(positionMs = 90_000L)
        assertTrue(shouldPublishSession(prev, next, elapsedSincePublishMs = 200L))
    }

    @Test
    fun `play pause is published`() {
        val prev = sess(playing = true)
        val next = sess(playing = false)
        assertTrue(shouldPublishSession(prev, next, elapsedSincePublishMs = 50L))
    }

    @Test
    fun `track change is published`() {
        val prev = sess(title = "A")
        val next = sess(title = "B")
        assertTrue(shouldPublishSession(prev, next, elapsedSincePublishMs = 50L))
    }

    @Test
    fun `null to session is published`() {
        assertTrue(shouldPublishSession(null, sess(), elapsedSincePublishMs = 0L))
    }
}
