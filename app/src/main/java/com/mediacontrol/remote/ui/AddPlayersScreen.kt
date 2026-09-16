package com.mediacontrol.remote.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.mediacontrol.remote.data.CombinedMediaSource
import com.mediacontrol.remote.data.CuratedPlayer
import com.mediacontrol.remote.data.CuratedPlayersRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Checking a row adds it to the Players list; unchecking is how a curated player is removed. */
class AddPlayersViewModel(
    private val mediaSource: CombinedMediaSource,
    private val curated: CuratedPlayersRepository,
) : ViewModel() {

    data class Row(val packageName: String, val label: String, val chosen: Boolean)

    val rows: StateFlow<List<Row>> = combine(mediaSource.phoneApps, curated.players) { apps, chosen ->
        val picked = chosen.map { it.packageName }.toSet()
        apps.map { Row(it.packageName, it.label, it.packageName in picked) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        mediaSource.requestApps()
    }

    fun toggle(row: Row) {
        if (row.chosen) curated.remove(row.packageName)
        else curated.add(CuratedPlayer(row.packageName, row.label))
    }

    fun refresh() {
        mediaSource.requestApps()
    }

    suspend fun icon(packageName: String): ByteArray? = mediaSource.appIcon(packageName)
}

class AddPlayersViewModelFactory(
    private val mediaSource: CombinedMediaSource,
    private val curated: CuratedPlayersRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AddPlayersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AddPlayersViewModel(mediaSource, curated) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}

@Composable
fun AddPlayersScreen(
    navController: NavHostController,
    viewModel: AddPlayersViewModel,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    SecondaryScaffold(navController = navController) {
        item {
            ListHeader {
                Text("Add players")
            }
        }
        if (rows.isEmpty()) {
            item {
                Text(
                    text = "No apps from phone yet",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                FilledTonalButton(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Refresh") },
                )
            }
        }
        items(rows) { row ->
            CheckboxButton(
                checked = row.chosen,
                onCheckedChange = { viewModel.toggle(row) },
                modifier = Modifier.fillMaxWidth(),
                icon = { AppIcon(packageName = row.packageName, load = viewModel::icon) },
                label = { Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
