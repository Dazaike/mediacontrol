package com.mediacontrol.remote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.stayPrefs by preferencesDataStore("stay_open_prefs")
private val KEY_MINUTES = intPreferencesKey("stay_open_minutes")

/** Minutes the app stays up after the screen goes off; the choices offered in Settings. */
val STAY_OPEN_CHOICES = listOf(0, 2, 5, 10, 30)

fun stayOpenLabel(minutes: Int): String = if (minutes <= 0) "Off" else "$minutes min"

/**
 * How long the player stays on screen after the wrist drops. 0 (default) leaves the
 * system's normal behaviour: back to the watch face.
 */
class StayOpenSettings(private val appContext: Context) {
    val minutes: Flow<Int> = appContext.stayPrefs.data.map { (it[KEY_MINUTES] ?: 0).coerceAtLeast(0) }

    suspend fun setMinutes(minutes: Int) {
        appContext.stayPrefs.edit { it[KEY_MINUTES] = minutes.coerceAtLeast(0) }
    }
}
