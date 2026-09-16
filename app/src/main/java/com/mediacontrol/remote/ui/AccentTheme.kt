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
    BLUE(label = "Blue", primary = Color(0xFF2D7BFF), secondary = Color(0xFF7FB2FF)),
    PURPLE(label = "Purple", primary = Color(0xFF8B5CFF), secondary = Color(0xFFC0A3FF)),
    EMERALD(label = "Emerald", primary = Color(0xFF00C98A), secondary = Color(0xFF5FEFC0)),
    AMBER(label = "Amber", primary = Color(0xFFFF9F0A), secondary = Color(0xFFFFD166)),
    CORAL(label = "Coral", primary = Color(0xFFFF4D3D), secondary = Color(0xFFFF9585)),
    MONOCHROME(label = "Monochrome", primary = Color.White, secondary = Color(0xFFD6D6D6)),
}

/**
 * Maps this accent onto a Wear Material 3 [ColorScheme].
 *
 * Token wiring that matters (verified against compose-material3 1.5.0 tokens):
 * tonal buttons and list rows fill from `surfaceContainer` and label from
 * `onSurface`; filled buttons fill from `primary`.
 *
 * Buttons are deliberately dark — accent-tinted rather than grey, so the theme still
 * reads — with pure white glyphs and labels on top. `primary` stays vivid because it
 * also drives the volume level indicator; the play button overrides its own fill at
 * the call site instead of dimming the token for everyone.
 */
fun AccentColor.toColorScheme(): ColorScheme = ColorScheme(
    primary = lerp(primary, Color.White, 0.10f),
    primaryDim = primary,
    primaryContainer = lerp(primary, Color.Black, 0.70f),
    onPrimary = Color.White,
    onPrimaryContainer = Color.White,
    secondary = secondary,
    secondaryDim = secondary,
    secondaryContainer = lerp(secondary, Color.Black, 0.70f),
    onSecondary = Color.White,
    onSecondaryContainer = Color.White,
    tertiary = secondary,
    tertiaryDim = secondary,
    tertiaryContainer = lerp(secondary, Color.Black, 0.70f),
    onTertiary = Color.White,
    onTertiaryContainer = Color.White,
    surfaceContainerLow = lerp(primary, Color.Black, 0.86f),
    surfaceContainer = lerp(primary, Color.Black, 0.78f),
    surfaceContainerHigh = lerp(primary, Color.Black, 0.68f),
    onSurface = Color.White,
    onSurfaceVariant = secondary,
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
