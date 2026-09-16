package com.mediacontrol.remote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val PLAYERS_PREFS = "watch_players_prefs"
private const val KEY_CURATED = "curated_players"
private const val KEY_AUTO_START = "auto_start"
private const val SEPARATOR = '\u0001'
private val Context.playerPrefs by preferencesDataStore(PLAYERS_PREFS)

data class CuratedPlayer(val packageName: String, val label: String)

/** User-chosen players, persisted as "pkg\u0001label" entries in a string set. */
class CuratedPlayersRepository(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _players = MutableStateFlow<List<CuratedPlayer>>(emptyList())
    val players: StateFlow<List<CuratedPlayer>> = _players.asStateFlow()

    init {
        scope.launch { reload() }
    }

    fun add(player: CuratedPlayer) {
        scope.launch {
            mutate { current ->
                current.filterNot { it.startsWith(player.packageName + SEPARATOR) } +
                    "${player.packageName}$SEPARATOR${player.label}"
            }
        }
    }

    fun remove(packageName: String) {
        scope.launch {
            mutate { current -> current.filterNot { it.startsWith(packageName + SEPARATOR) } }
        }
    }

    private suspend fun mutate(transform: (Set<String>) -> Collection<String>) {
        try {
            appContext.playerPrefs.edit { prefs ->
                val key = stringSetPreferencesKey(KEY_CURATED)
                prefs[key] = transform(prefs[key].orEmpty()).toSet()
            }
        } catch (e: Exception) {
            // Best-effort persistence; the reload below still republishes what stuck.
        }
        reload()
    }

    private suspend fun reload() {
        val stored = try {
            appContext.playerPrefs.data
                .map { it[stringSetPreferencesKey(KEY_CURATED)].orEmpty() }
                .first()
        } catch (e: Exception) {
            emptySet()
        }
        _players.value = stored
            .mapNotNull { entry ->
                val at = entry.indexOf(SEPARATOR)
                if (at <= 0) null else CuratedPlayer(entry.substring(0, at), entry.substring(at + 1))
            }
            .sortedBy { it.label.lowercase() }
    }
}

/**
 * "Auto-start music": when the phone reports nothing playing, ask it to resume the
 * last player instead of waiting for a tap. Shares the players DataStore file.
 */
class AutoStartRepository(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        scope.launch {
            _enabled.value = try {
                appContext.playerPrefs.data
                    .map { it[booleanPreferencesKey(KEY_AUTO_START)] ?: false }
                    .first()
            } catch (e: Exception) {
                false
            }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        scope.launch {
            try {
                appContext.playerPrefs.edit { it[booleanPreferencesKey(KEY_AUTO_START)] = value }
            } catch (e: Exception) {
                // Best-effort persistence; the in-memory value still applies this session.
            }
        }
    }
}
