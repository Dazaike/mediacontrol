package com.mediacontrol.remote.relay

import com.mediacontrol.remote.soundcore.SoundcoreMode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {

    @Test
    fun `simple commands round-trip through encode and decode`() {
        listOf(
            RelayCommand.Play,
            RelayCommand.Pause,
            RelayCommand.Toggle,
            RelayCommand.Next,
            RelayCommand.Previous,
            RelayCommand.VolumeUp,
            RelayCommand.VolumeDown,
            RelayCommand.Subscribe,
            RelayCommand.Unsubscribe,
        ).forEach { cmd -> assertEquals(cmd, RelayCommand.decode(cmd.encode())) }
    }

    @Test
    fun `seek round-trips its position`() {
        val decoded = RelayCommand.decode(RelayCommand.Seek(45_000L).encode())
        assertTrue(decoded is RelayCommand.Seek)
        assertEquals(45_000L, (decoded as RelayCommand.Seek).posMs)
    }

    @Test
    fun `select round-trips its package`() {
        val decoded = RelayCommand.decode(RelayCommand.Select("com.spotify.music").encode())
        assertTrue(decoded is RelayCommand.Select)
        assertEquals("com.spotify.music", (decoded as RelayCommand.Select).pkg)
    }

    @Test
    fun `volume round-trips its value`() {
        val decoded = RelayCommand.decode(RelayCommand.SetVolume(9).encode())
        assertTrue(decoded is RelayCommand.SetVolume)
        assertEquals(9, (decoded as RelayCommand.SetVolume).volume)
    }

    @Test
    fun `skipToQueueItem round-trips its queueId`() {
        val decoded = RelayCommand.decode(RelayCommand.SkipToQueueItem(42L).encode())
        assertTrue(decoded is RelayCommand.SkipToQueueItem)
        assertEquals(42L, (decoded as RelayCommand.SkipToQueueItem).queueId)
    }

    @Test
    fun `soundcore mode round-trips its mode`() {
        val decoded = RelayCommand.decode(RelayCommand.Soundcore(SoundcoreMode.TRANSPARENCY).encode())
        assertTrue(decoded is RelayCommand.Soundcore)
        assertEquals(SoundcoreMode.TRANSPARENCY, (decoded as RelayCommand.Soundcore).mode)
    }

    @Test
    fun `malformed payload decodes to null instead of throwing`() {
        assertNull(RelayCommand.decode("not json".toByteArray()))
        assertNull(RelayCommand.decode("{}".toByteArray()))
        assertNull(RelayCommand.decode("select|".toByteArray()))
        assertNull(RelayCommand.decode("soundcore|".toByteArray()))
    }
}
