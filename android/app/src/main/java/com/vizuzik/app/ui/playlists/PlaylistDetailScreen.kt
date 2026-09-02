package com.vizuzik.app.ui.playlists

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.ui.components.EmptyState
import com.vizuzik.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = { showRenameDialog = true }) { Icon(Icons.Filled.Edit, contentDescription = "Renommer") }
                    IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer") }
                },
            )
        },
    ) { padding ->
        val tracks = playlist?.tracks.orEmpty()
        if (tracks.isEmpty()) {
            EmptyState(
                title = "Playlist vide",
                body = "Ajoutez des morceaux depuis l'écran Morceaux.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
                items(tracks, key = Track::id) { track ->
                    val index = tracks.indexOf(track)
                    TrackRow(
                        track = track,
                        onClick = { viewModel.play(index) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { viewModel.moveTrack(index, (index - 1).coerceAtLeast(0)) }, enabled = index > 0) {
                                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Monter")
                                }
                                IconButton(
                                    onClick = { viewModel.moveTrack(index, (index + 1).coerceAtMost(tracks.lastIndex)) },
                                    enabled = index < tracks.lastIndex,
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descendre")
                                }
                                IconButton(onClick = { viewModel.removeTrack(track.id) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Retirer")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        var name by remember { mutableStateOf(playlist?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renommer la playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { viewModel.rename(name); showRenameDialog = false }, enabled = name.isNotBlank()) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Annuler") } },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer la playlist ?") },
            text = { Text("Cette action est irréversible.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(); showDeleteDialog = false; onBack() }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Annuler") } },
        )
    }
}
