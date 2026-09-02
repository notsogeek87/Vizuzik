package com.vizuzik.app.ui.tracks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.R
import com.vizuzik.app.domain.model.Playlist
import com.vizuzik.app.domain.model.SortOrder
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.ui.components.EmptyState
import com.vizuzik.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksScreen(viewModel: TracksViewModel = hiltViewModel()) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var trackForPlaylistPicker by remember { mutableStateOf<Track?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_tracks)) },
                actions = {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.Filled.Sort, contentDescription = "Trier")
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Titre") }, onClick = { viewModel.setSortOrder(SortOrder.TITLE); sortMenuOpen = false })
                        DropdownMenuItem(text = { Text("Artiste") }, onClick = { viewModel.setSortOrder(SortOrder.ARTIST); sortMenuOpen = false })
                        DropdownMenuItem(text = { Text("Album") }, onClick = { viewModel.setSortOrder(SortOrder.ALBUM); sortMenuOpen = false })
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.action_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (tracks.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.library_empty_title),
                    body = stringResource(R.string.library_empty_body),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(tracks, key = Track::id) { track ->
                        TrackRow(
                            track = track,
                            onClick = { viewModel.playTrack(track) },
                            trailingContent = {
                                IconButton(onClick = { trackForPlaylistPicker = track }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Ajouter à une playlist")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    trackForPlaylistPicker?.let { track ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { trackForPlaylistPicker = null },
            onPlaylistSelected = { playlistId ->
                viewModel.addToPlaylist(playlistId, track)
                trackForPlaylistPicker = null
            },
            onCreateNew = { name ->
                viewModel.createPlaylistWithTrack(name, track)
                trackForPlaylistPicker = null
            },
        )
    }
}

@Composable
private fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlaylistSelected: (Long) -> Unit,
    onCreateNew: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter à une playlist") },
        text = {
            Column {
                playlists.forEach { playlist ->
                    TextButton(onClick = { onPlaylistSelected(playlist.id) }) {
                        Text(playlist.name, modifier = Modifier.fillMaxWidth())
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nouvelle playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreateNew(newName) }, enabled = newName.isNotBlank()) { Text("Créer et ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
