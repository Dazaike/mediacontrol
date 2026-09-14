package com.mediacontrol.remote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediacontrol.remote.data.CombinedMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Volume control for the phone companion: controls the phone's music stream volume
 * over the Wearable Data Layer.
 */
class VolumeViewModel(private val mediaSource: CombinedMediaSource) : ViewModel() {

    private val localVolume = MutableStateFlow<Int?>(null)

    val volume: StateFlow<Int> = combine(mediaSource.volume, localVolume) { remote, local ->
        local ?: remote
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mediaSource.volume.value)

    val maxVolume: StateFlow<Int> = mediaSource.maxVolume
    val available: Boolean = true

    fun setVolume(value: Int) {
        localVolume.value = value
        mediaSource.setVolume(value)
    }

    fun nudge(steps: Int) {
        val max = maxVolume.value.coerceAtLeast(1)
        val next = (volume.value + steps).coerceIn(0, max)
        localVolume.value = next
        mediaSource.nudgeVolume(steps)
    }
}

class VolumeViewModelFactory(
    private val mediaSource: CombinedMediaSource,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VolumeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VolumeViewModel(mediaSource) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
