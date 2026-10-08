package com.mediacontrol.remote.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediacontrol.remote.data.StayOpenSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StayOpenViewModel(private val settings: StayOpenSettings) : ViewModel() {
    val minutes: StateFlow<Int> = settings.minutes.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setMinutes(minutes: Int) {
        viewModelScope.launch { settings.setMinutes(minutes) }
    }
}

class StayOpenViewModelFactory(private val appContext: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return StayOpenViewModel(StayOpenSettings(appContext.applicationContext)) as T
    }
}
