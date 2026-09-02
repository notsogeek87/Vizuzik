package com.vizuzik.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.domain.model.QueueItem
import com.vizuzik.app.ui.components.EmptyState
import com.vizuzik.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File d'attente") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = viewModel::clearQueue) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Vider la file")
                    }
                },
            )
        },
    ) { padding ->
        if (state.queue.isEmpty()) {
            EmptyState(title = "File d'attente vide", body = "Lancez un morceau pour remplir la file.", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
                items(state.queue, key = { it.position }) { item: QueueItem ->
                    val isCurrent = item.position == state.queueIndex
                    TrackRow(
                        track = item.track,
                        isPlaying = isCurrent,
                        onClick = { viewModel.playQueueItem(item.position) },
                        modifier = if (isCurrent) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else Modifier,
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { viewModel.moveQueueItem(item.position, (item.position - 1).coerceAtLeast(0)) },
                                    enabled = item.position > 0,
                                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Monter") }
                                IconButton(
                                    onClick = { viewModel.moveQueueItem(item.position, (item.position + 1).coerceAtMost(state.queue.lastIndex)) },
                                    enabled = item.position < state.queue.lastIndex,
                                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descendre") }
                                IconButton(onClick = { viewModel.removeFromQueue(item.position) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Retirer de la file")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
