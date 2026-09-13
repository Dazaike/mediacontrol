package com.mediacontrol.remote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mediacontrol.remote.data.CombinedMediaSource
import kotlinx.coroutines.flow.StateFlow

/**
 * Volume control for the phone companion: controls the phone's music stream volume
 * over the Wearable Data Layer.
 */
class VolumeViewModel(private val mediaSource: CombinedMediaSource) : ViewModel() {

    val volume: StateFlow<Int> = mediaSource.volume
    val maxVolume: StateFlow<Int> = mediaSource.maxVolume
    val available: Boolean = true

    fun setVolume(value: Int) {
        mediaSource.setVolume(value)
    }

    fun nudge(steps: Int) {
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
