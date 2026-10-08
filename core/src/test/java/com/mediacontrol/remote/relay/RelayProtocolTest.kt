package com.mediacontrol.remote.relay

import com.mediacontrol.remote.lyrics.LyricsProvider
import com.mediacontrol.remote.lyrics.lyricsTrackKey
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
    fun `lyrics commands round-trip`() {
        LyricsProvider.entries.forEach { p ->
            assertEquals(RelayCommand.RequestLyrics(p), RelayCommand.decode(RelayCommand.RequestLyrics(p).encode()))
        }
        assertEquals(RelayCommand.LyricsActive(true), RelayCommand.decode(RelayCommand.LyricsActive(true).encode()))
        assertEquals(RelayCommand.LyricsActive(false), RelayCommand.decode(RelayCommand.LyricsActive(false).encode()))
    }

    @Test
    fun `run action keeps pipes in the action id and rejects an empty id`() {
        val cmd = RelayCommand.RunAction("com.app.ACTION|TOGGLE_LIKE")
        assertEquals(cmd, RelayCommand.decode(cmd.encode()))
        assertNull(RelayCommand.decode("action|".toByteArray()))
        assertNull(RelayCommand.decode("action".toByteArray()))
    }

    @Test
    fun `malformed lyrics commands decode to null`() {
        assertNull(RelayCommand.decode("lyrics|NOPE".toByteArray()))
        assertNull(RelayCommand.decode("lyrics".toByteArray()))
        assertNull(RelayCommand.decode("lyricsActive|2".toByteArray()))
        assertNull(RelayCommand.decode("lyricsActive".toByteArray()))
    }

    @Test
    fun `lyrics sync keeps a pipe inside the track key`() {
        val key = lyricsTrackKey("A | B", "Artist|X", 200_500L)
        val msg = decodeLyricsSync(encodeLyricsSync(LyricsSyncMsg(12_345L, true, 180L, 99_000L, key)))
        assertEquals(LyricsSyncMsg(12_345L, true, 180L, 99_000L, key), msg)
        assertEquals(false, decodeLyricsSync(encodeLyricsSync(LyricsSyncMsg(0L, false, 0L, 1L, key)))?.playing)
    }

    @Test
    fun `malformed lyrics sync decodes to null`() {
        assertNull(decodeLyricsSync("abc|1|0|5|key".toByteArray()))
        assertNull(decodeLyricsSync("100|2|0|5|key".toByteArray()))
        assertNull(decodeLyricsSync("100|1|x|5|key".toByteArray()))
        assertNull(decodeLyricsSync("100|1|0|5".toByteArray()))
    }

    @Test
    fun `lyrics ack round-trips its stamp`() {
        assertEquals(RelayCommand.LyricsAck(42L), RelayCommand.decode(RelayCommand.LyricsAck(42L).encode()))
        assertNull(RelayCommand.decode("lyricsAck|x".toByteArray()))
    }

    @Test
    fun `malformed payload decodes to null instead of throwing`() {
        assertNull(RelayCommand.decode("not json".toByteArray()))
        assertNull(RelayCommand.decode("{}".toByteArray()))
        assertNull(RelayCommand.decode("select|".toByteArray()))
        assertNull(RelayCommand.decode("soundcore|".toByteArray()))
    }
}
