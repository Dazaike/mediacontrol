package com.mediacontrol.remote.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.wear.compose.material3.ColorScheme
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** User-selectable accent used for every primary control across the watch app. */
enum class AccentColor(val label: String, val primary: Color, val secondary: Color) {
    BLUE(label = "Blue", primary = Color(0xFF3872E0), secondary = Color(0xFF85B1FF)),
    PURPLE(label = "Purple", primary = Color(0xFF7C4DFF), secondary = Color(0xFFB39DFF)),
    EMERALD(label = "Emerald", primary = Color(0xFF1FA97C), secondary = Color(0xFF6FE0B7)),
    AMBER(label = "Amber", primary = Color(0xFFC77E00), secondary = Color(0xFFFFC966)),
    CORAL(label = "Coral", primary = Color(0xFFE2543A), secondary = Color(0xFFFF9C86)),
    MONOCHROME(label = "Monochrome", primary = Color(0xFF6B6B6B), secondary = Color(0xFFC7C7C7)),
}

/** Maps this accent onto a Wear Material 3 [ColorScheme], leaving remaining tokens at defaults. */
fun AccentColor.toColorScheme(): ColorScheme = ColorScheme(
    primary = primary,
    primaryDim = lerp(primary, Color.Black, 0.22f),
    primaryContainer = lerp(primary, Color.Black, 0.55f),
    onPrimary = Color.White,
    onPrimaryContainer = Color.White,
    secondary = secondary,
    secondaryDim = lerp(secondary, Color.Black, 0.22f),
    secondaryContainer = lerp(secondary, Color.Black, 0.55f),
    onSecondary = Color.Black,
    onSecondaryContainer = Color.White,
)

private const val THEME_PREFS = "watch_theme_prefs"
private const val KEY_ACCENT = "accent_color"
private val Context.themePrefs by preferencesDataStore(THEME_PREFS)

/** Persists the chosen [AccentColor] across watch app restarts. */
class ThemeRepository(private val appContext: Context) {
    private val _accent = MutableStateFlow(AccentColor.BLUE)
    val accent: StateFlow<AccentColor> = _accent.asStateFlow()

    suspend fun load() {
        val stored = try {
            appContext.themePrefs.data.map { it[stringPreferencesKey(KEY_ACCENT)] }.first()
        } catch (e: Exception) {
            null
        }
        _accent.value = stored?.let { name ->
            AccentColor.entries.firstOrNull { it.name == name }
        } ?: AccentColor.BLUE
    }

    suspend fun setAccent(accent: AccentColor) {
        _accent.value = accent
        try {
            appContext.themePrefs.edit { it[stringPreferencesKey(KEY_ACCENT)] = accent.name }
        } catch (e: Exception) {
            // Best-effort persistence; in-memory selection still applies this session.
        }
    }
}

class ThemeViewModel(private val repository: ThemeRepository) : ViewModel() {
    val accent: StateFlow<AccentColor> = repository.accent

    init {
        viewModelScope.launch { repository.load() }
    }

    fun setAccent(accent: AccentColor) {
        viewModelScope.launch { repository.setAccent(accent) }
    }
}

class ThemeViewModelFactory(private val appContext: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ThemeViewModel(ThemeRepository(appContext.applicationContext)) as T
    }
}
