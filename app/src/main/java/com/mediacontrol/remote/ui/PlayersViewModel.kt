package com.mediacontrol.remote.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.SessionCandidate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val PKG_SPOTIFY = "com.spotify.music"
const val PKG_YTM = "com.google.android.apps.youtube.music"
const val PKG_POCKET_CASTS = "au.com.shiftyjelly.pocketcasts"
const val PKG_SAMSUNG_MUSIC = "com.sec.android.app.music"

/** Fixed labels: these packages are almost never installed on the watch itself, so
 *  the watch can't resolve display names via PackageManager — the phone (where they
 *  actually live) resolves the real launcher label once relayed back as a session. */
private val KNOWN_PLAYER_LABELS = linkedMapOf(
    PKG_SPOTIFY to "Spotify",
    PKG_YTM to "YouTube Music",
    PKG_POCKET_CASTS to "Pocket Casts",
    PKG_SAMSUNG_MUSIC to "Samsung Music",
)

data class PlayerEntry(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val isPlaying: Boolean = false,
)

data class PlayersUiState(
    val known: List<PlayerEntry>,
    val others: List<PlayerEntry>,
)

class PlayersViewModel(
    private val mediaSource: CombinedMediaSource,
    private val appContext: Context,
) : ViewModel() {

    val uiState: StateFlow<PlayersUiState> = mediaSource.liveSessions
        .map { live -> compute(live, mediaSource.readLastPackage()) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            compute(emptyList(), mediaSource.readLastPackage()),
        )

    fun refresh() {
        mediaSource.refreshSessions()
    }

    /**
     * Tries a local launch (harmless no-op unless the package happens to be installed
     * on this device), then always routes select through [CombinedMediaSource] — which
     * forwards it to the phone as a launch+select command when nothing local matches.
     */
    fun open(packageName: String) {
        try {
            appContext.packageManager.getLaunchIntentForPackage(packageName)?.let {
                appContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            // No local launch intent or blocked launch: relay-select still applies.
        }
        mediaSource.select(packageName)
    }

    private fun compute(
        live: List<SessionCandidate>,
        lastPackage: String?,
    ): PlayersUiState {
        val liveLabels = live.associate { it.packageName to it.appLabel }
        // Known list is always shown — the watch usually can't resolve these packages
        // locally (they live on the phone), so inclusion never depends on local install.
        val known = KNOWN_PLAYER_LABELS.entries
            .map { (pkg, fallbackLabel) -> PlayerEntry(pkg, liveLabels[pkg] ?: fallbackLabel, true) }
            .sortedWith(compareBy({ it.packageName != lastPackage }, { it.label }))
        // Every other live audible session relayed from the phone — covers any app
        // (e.g. a third-party player) beyond the fixed known-player presets above.
        val others = live
            .filter { it.packageName !in KNOWN_PLAYER_LABELS }
            .map { candidate ->
                PlayerEntry(
                    packageName = candidate.packageName,
                    label = candidate.appLabel,
                    installed = appContext.packageManager.getLaunchIntentForPackage(candidate.packageName) != null,
                    isPlaying = candidate.isPlaying,
                )
            }
            .sortedWith(compareBy({ !it.isPlaying }, { it.label }))
        return PlayersUiState(known, others)
    }
}

class PlayersViewModelFactory(
    private val mediaSource: CombinedMediaSource,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlayersViewModel(mediaSource, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
