package com.mediacontrol.remote.ui

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediacontrol.remote.data.AutoStartRepository
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.ControlledSession
import com.mediacontrol.remote.data.PendingLaunch
import com.mediacontrol.remote.data.btStatusLine
import com.mediacontrol.remote.data.nextBanner
import com.mediacontrol.remote.soundcore.SoundcoreMode
import com.mediacontrol.remote.soundcore.SoundcoreStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

@Stable
class NowPlayingViewModel(
    private val mediaSource: CombinedMediaSource,
    private val autoStartRepo: AutoStartRepository,
) : ViewModel() {

    val uiState: StateFlow<NowPlayingUiState> = mediaSource.activeSession
        .map { session ->
            if (session == null) NowPlayingUiState.NoSession
            else NowPlayingUiState.Ready(session)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUiState.Loading)

    val artworkBytes: StateFlow<ByteArray?> = mediaSource.artworkBytes

    val pendingLaunch: StateFlow<PendingLaunch?> = mediaSource.pendingLaunch

    private val localVolume = MutableStateFlow<Int?>(null)
    val volume: StateFlow<Int> = combine(mediaSource.volume, localVolume) { remote, local -> local ?: remote }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mediaSource.volume.value)
    val maxVolume: StateFlow<Int> = mediaSource.maxVolume

    private val _volumeVisible = MutableStateFlow(false)
    val volumeVisible: StateFlow<Boolean> = _volumeVisible.asStateFlow()
    private var volumeHideJob: Job? = null

    fun nudgeVolume(steps: Int) {
        val max = maxVolume.value.coerceAtLeast(1)
        val next = (volume.value + steps).coerceIn(0, max)
        localVolume.value = next
        mediaSource.setVolume(next)
        _volumeVisible.value = true
        volumeHideJob?.cancel()
        volumeHideJob = viewModelScope.launch {
            delay(1_500)
            _volumeVisible.value = false
            localVolume.value = null
        }
    }

    val soundcore: StateFlow<SoundcoreStatus> = mediaSource.soundcore

    fun setSoundcoreMode(mode: SoundcoreMode) {
        mediaSource.soundcoreMode(mode)
    }

    private var rotaryAccum = 0f

    /**
     * Returns true when a volume step was actually applied (caller fires haptics).
     *
     * The rotary reports scroll pixels, where a clockwise turn scrolls a list *down*
     * and so comes through positive. Volume has the opposite convention — clockwise
     * means louder — hence the negated step.
     */
    fun onRotary(pixels: Float, stepPx: Float): Boolean {
        rotaryAccum += pixels
        val steps = (rotaryAccum / stepPx).toInt()
        if (steps == 0) return false
        rotaryAccum -= steps * stepPx
        nudgeVolume(-steps)
        return true
    }

    val statusLine: StateFlow<String> = mediaSource.btAudioState
        .map { btStatusLine(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), btStatusLine(mediaSource.btAudioState.value))

    private val _banner = MutableStateFlow<Pair<String, String>?>(null)
    val banner: StateFlow<Pair<String, String>?> = _banner.asStateFlow()

    fun dismissBanner() {
        _banner.value = null
    }

    val autoStart: StateFlow<Boolean> = autoStartRepo.enabled

    fun setAutoStart(enabled: Boolean) {
        autoStartRepo.setEnabled(enabled)
    }

    /** Icon bytes for a phone package, or null until the catalog lands. */
    suspend fun appIcon(packageName: String): ByteArray? = mediaSource.appIcon(packageName)

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
        // Auto-start: nothing playing + toggle on -> ask the phone to resume its last
        // player.
        //
        // `relaySession` starts null, so uiState reports NoSession for the second or
        // two before the phone's first DataItem lands — and briefly again whenever the
        // relay drops. Acting on that transient launched an app the moment the watch
        // app opened, even while the phone was already playing. collectLatest plus a
        // settle delay means the idle state has to still hold after the relay has had
        // time to report; any session arriving first cancels the pending attempt.
        viewModelScope.launch {
            var lastAttemptAt = 0L
            combine(uiState, autoStart) { state, on -> on && state is NowPlayingUiState.NoSession }
                .distinctUntilChanged()
                .collectLatest { shouldStart ->
                    if (!shouldStart) return@collectLatest
                    delay(AUTO_START_SETTLE_MS)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAttemptAt < AUTO_START_COOLDOWN_MS) return@collectLatest
                    lastAttemptAt = now
                    mediaSource.play()
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

private const val AUTO_START_COOLDOWN_MS = 30_000L

/** How long "nothing playing" must hold before auto-start believes it. */
private const val AUTO_START_SETTLE_MS = 6_000L

class NowPlayingViewModelFactory(
    private val mediaSource: CombinedMediaSource,
    private val autoStartRepo: AutoStartRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NowPlayingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NowPlayingViewModel(mediaSource, autoStartRepo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
