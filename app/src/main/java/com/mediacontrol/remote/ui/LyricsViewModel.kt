package com.mediacontrol.remote.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.LyricsSettings
import com.mediacontrol.remote.data.LyricsUiState
import com.mediacontrol.remote.lyrics.LyricsProvider
import com.mediacontrol.remote.lyrics.lyricsTrackKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Lyrics flows only run while a lyrics screen is collecting them (plus a short grace).
private const val SHARE_TIMEOUT_MS = 5_000L

/** Shared by the Lyrics page, the Lyrics source picker and Settings. */
class LyricsViewModel(
    private val source: CombinedMediaSource,
    private val settings: LyricsSettings,
) : ViewModel() {

    /** Null until the persisted choice has loaded, so the page never requests with a stale default. */
    val provider: StateFlow<LyricsProvider?> = settings.provider
        .map<LyricsProvider, LyricsProvider?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), null)

    /** Blank when nothing with a title is playing. */
    val currentTrackKey: StateFlow<String> = source.activeSession
        .map { s -> if (s == null || s.title.isNullOrBlank()) "" else lyricsTrackKey(s.title, s.artist, s.durationMs) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), "")

    /** Relay state, narrowed to the track on screen: another track's lyrics read as Loading. */
    val uiState: StateFlow<LyricsUiState> = combine(source.lyricsState, currentTrackKey) { state, key ->
        when {
            key.isBlank() -> LyricsUiState.None("")
            state is LyricsUiState.Idle -> LyricsUiState.Loading(key)
            state is LyricsUiState.Loading && state.trackKey == key -> state
            state is LyricsUiState.Ready && state.trackKey == key -> state
            state is LyricsUiState.None && state.trackKey == key -> state
            else -> LyricsUiState.Loading(key)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), LyricsUiState.Idle)

    /** Phone playback position for the current track; 0 until a beacon for this track arrives. */
    fun positionMs(nowElapsedMs: Long): Long =
        if (source.lyricsSyncTrackKey.value == currentTrackKey.value) source.lyricsClock.positionAt(nowElapsedMs) else 0L

    fun ensureLyrics(provider: LyricsProvider) = source.ensureLyrics(provider)

    fun onPageActive(active: Boolean) = source.setLyricsActive(active)

    /**
     * Only persists the choice. The lyrics screen notices the new provider when it is next
     * live and requests then, so picking one from Settings never fetches anything.
     */
    fun setProvider(provider: LyricsProvider) {
        viewModelScope.launch { settings.setProvider(provider) }
    }
}

class LyricsViewModelFactory(
    private val source: CombinedMediaSource,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LyricsViewModel(source, LyricsSettings(appContext.applicationContext)) as T
    }
}
