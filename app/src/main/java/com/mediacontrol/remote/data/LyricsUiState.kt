package com.mediacontrol.remote.data

import com.mediacontrol.remote.lyrics.Lyrics

/** Watch-side lyrics lifecycle for the track last requested from the phone. */
sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data class Loading(val trackKey: String) : LyricsUiState
    data class Ready(val trackKey: String, val lyrics: Lyrics) : LyricsUiState
    data class None(val trackKey: String) : LyricsUiState
}
