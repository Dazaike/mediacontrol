package com.mediacontrol.remote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediacontrol.remote.data.CombinedMediaSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

import com.mediacontrol.remote.data.QueueTrack

data class QueueUiState(
    val currentTitle: String?,
    val currentArtist: String?,
    val queueTitle: String,
    val items: List<QueueTrack>,
    val available: Boolean,
)

class QueueViewModel(private val mediaSource: CombinedMediaSource) : ViewModel() {

    val uiState: StateFlow<QueueUiState> = combine(
        mediaSource.activeSession,
        mediaSource.queue,
        mediaSource.queueTitle,
    ) { session, queueList, title ->
        QueueUiState(
            currentTitle = session?.title ?: session?.appLabel,
            currentArtist = session?.artist,
            queueTitle = title,
            items = queueList,
            available = queueList.isNotEmpty(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        QueueUiState(null, null, "Queue", emptyList(), false),
    )

    fun skipTo(queueId: Long) {
        mediaSource.skipToQueueItem(queueId)
    }
}

class QueueViewModelFactory(
    private val mediaSource: CombinedMediaSource,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(QueueViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return QueueViewModel(mediaSource) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
