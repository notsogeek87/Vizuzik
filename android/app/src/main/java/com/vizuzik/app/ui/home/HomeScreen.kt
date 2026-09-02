package com.vizuzik.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.R
import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.LibraryScanState
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.ui.components.AlbumCard
import com.vizuzik.app.ui.components.EmptyState
import com.vizuzik.app.ui.components.SectionHeader
import com.vizuzik.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val recentAlbums by viewModel.recentAlbums.collectAsStateWithLifecycle()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val libraryIsEmpty by viewModel.libraryIsEmpty.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = viewModel::refreshLibrary) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh_library))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        if (scanState is LibraryScanState.Scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
        }

        if (libraryIsEmpty && scanState !is LibraryScanState.Scanning) {
            EmptyState(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (recentAlbums.isNotEmpty()) {
                item { SectionHeader(title = "Albums récemment ajoutés") }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        items(recentAlbums, key = Album::id) { album ->
                            AlbumCard(album = album, onClick = { onOpenAlbum(album.id) })
                        }
                    }
                }
            }

            if (recentlyPlayed.isNotEmpty()) {
                item { SectionHeader(title = "Récemment joués") }
                items(recentlyPlayed, key = Track::id) { track ->
                    TrackRow(
                        track = track,
                        onClick = { viewModel.playTrack(track, recentlyPlayed) },
                    )
                }
            }
        }
    }
}
