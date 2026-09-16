package com.mediacontrol.remote.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.CuratedPlayersRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PlayerEntry(val packageName: String, val label: String, val isPlaying: Boolean = false)

data class PlayersUiState(val players: List<PlayerEntry>)

/** The Players list is exactly what the user curated on [AddPlayersScreen]; nothing is inferred. */
class PlayersViewModel(
    private val mediaSource: CombinedMediaSource,
    private val curated: CuratedPlayersRepository,
    private val appContext: Context,
) : ViewModel() {

    val uiState: StateFlow<PlayersUiState> =
        combine(curated.players, mediaSource.liveSessions) { chosen, live ->
            val playing = live.filter { it.isPlaying }.map { it.packageName }.toSet()
            PlayersUiState(chosen.map { PlayerEntry(it.packageName, it.label, it.packageName in playing) })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayersUiState(emptyList()))

    /**
     * Tries a local launch (harmless no-op unless the package happens to be installed
     * on this device), then always routes select through [CombinedMediaSource] — which
     * forwards it to the phone as a launch+select command when nothing local matches.
     */
    fun open(entry: PlayerEntry) {
        try {
            appContext.packageManager.getLaunchIntentForPackage(entry.packageName)?.let {
                appContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            // No local launch intent or blocked launch: relay-select still applies.
        }
        mediaSource.select(entry.packageName, entry.label)
    }

    fun remove(entry: PlayerEntry) {
        curated.remove(entry.packageName)
    }

    /** Launcher icon bytes relayed from the phone, for the row's leading slot. */
    suspend fun icon(packageName: String): ByteArray? = mediaSource.appIcon(packageName)
}

class PlayersViewModelFactory(
    private val mediaSource: CombinedMediaSource,
    private val curated: CuratedPlayersRepository,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlayersViewModel(mediaSource, curated, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
