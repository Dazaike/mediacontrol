package com.mediacontrol.remote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediacontrol.remote.lyrics.LyricsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lyricsPrefs by preferencesDataStore("lyrics_prefs")
private val KEY_PROVIDER = stringPreferencesKey("lyrics_provider")

/** Persists which lyrics provider the watch asks the phone to use. */
class LyricsSettings(private val appContext: Context) {
    /** Unknown or missing values resolve to [LyricsProvider.AUTO]. */
    val provider: Flow<LyricsProvider> = appContext.lyricsPrefs.data.map { prefs ->
        LyricsProvider.entries.firstOrNull { it.name == prefs[KEY_PROVIDER] } ?: LyricsProvider.AUTO
    }

    suspend fun setProvider(provider: LyricsProvider) {
        appContext.lyricsPrefs.edit { it[KEY_PROVIDER] = provider.name }
    }
}
