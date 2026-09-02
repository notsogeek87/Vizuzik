package com.vizuzik.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import com.vizuzik.app.domain.model.Artist
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.ui.components.AlbumCard
import com.vizuzik.app.ui.components.ArtistCard
import com.vizuzik.app.ui.components.EmptyState
import com.vizuzik.app.ui.components.SectionHeader
import com.vizuzik.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.action_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) } },
            )
        },
    ) { padding ->
        if (query.isBlank()) {
            EmptyState(title = "Rechercher", body = "Titre, artiste ou album", modifier = Modifier.padding(padding))
        } else if (result.isEmpty) {
            EmptyState(title = "Aucun résultat", body = "Essayez un autre terme de recherche.", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (result.artists.isNotEmpty()) {
                    item { SectionHeader(title = "Artistes") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                            items(result.artists, key = Artist::id) { artist -> ArtistCard(artist = artist, onClick = { onOpenArtist(artist.id) }) }
                        }
                    }
                }
                if (result.albums.isNotEmpty()) {
                    item { SectionHeader(title = "Albums") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                            items(result.albums, key = Album::id) { album -> AlbumCard(album = album, onClick = { onOpenAlbum(album.id) }) }
                        }
                    }
                }
                if (result.tracks.isNotEmpty()) {
                    item { SectionHeader(title = "Morceaux") }
                    items(result.tracks, key = Track::id) { track ->
                        TrackRow(track = track, onClick = { viewModel.playTrack(track, result.tracks) })
                    }
                }
            }
        }
    }
}
