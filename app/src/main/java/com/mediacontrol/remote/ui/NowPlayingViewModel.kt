package com.mediacontrol.remote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.ControlledSession
import com.mediacontrol.remote.data.btStatusLine
import com.mediacontrol.remote.data.nextBanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface NowPlayingUiState {
    data object Loading : NowPlayingUiState

    data object NoSession : NowPlayingUiState

    data class Ready(
        val session: ControlledSession,
        val showQueueUnavailableHint: Boolean = false,
    ) : NowPlayingUiState
}

class NowPlayingViewModel(
    private val mediaSource: CombinedMediaSource,
) : ViewModel() {

    val uiState: StateFlow<NowPlayingUiState> = mediaSource.activeSession
        .map { session ->
            if (session == null) NowPlayingUiState.NoSession
            else NowPlayingUiState.Ready(session)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUiState.Loading)

    val artworkBytes: StateFlow<ByteArray?> = mediaSource.artworkBytes

    val statusLine: StateFlow<String> = mediaSource.btAudioState
        .map { btStatusLine(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), btStatusLine(mediaSource.btAudioState.value))

    private val _banner = MutableStateFlow<Pair<String, String>?>(null)
    val banner: StateFlow<Pair<String, String>?> = _banner.asStateFlow()

    fun dismissBanner() {
        _banner.value = null
    }

    init {
        mediaSource.refreshSessions()
        viewModelScope.launch {
            var prevConnected: Boolean? = null
            combine(mediaSource.activeSession, mediaSource.btAudioState) { session, btState ->
                Triple(session?.isPlaying == true, btState.connected, _banner.value)
            }.collect { (playing, connected, current) ->
                val prev = prevConnected
                prevConnected = connected
                // Cold start already-disconnected is a baseline, not a transition.
                if (prev == null) return@collect
                _banner.value = nextBanner(prev, connected, playing, current)
            }
        }
    }

    fun toggle() {
        viewModelScope.launch { mediaSource.togglePlayPause() }
    }

    fun previous() {
        viewModelScope.launch { mediaSource.previous() }
    }

    fun next() {
        viewModelScope.launch { mediaSource.next() }
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch { mediaSource.seekTo(positionMs) }
    }
}

class NowPlayingViewModelFactory(
    private val mediaSource: CombinedMediaSource,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NowPlayingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NowPlayingViewModel(mediaSource) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
