package com.vizuzik.app.ui.artists

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.ui.components.AlbumCard
import com.vizuzik.app.ui.components.SectionHeader
import com.vizuzik.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(onBack: () -> Unit, onOpenAlbum: (String) -> Unit, viewModel: ArtistDetailViewModel = hiltViewModel()) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val artistName = tracks.firstOrNull()?.artist ?: albums.firstOrNull()?.artist.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artistName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
            if (albums.isNotEmpty()) {
                item { SectionHeader(title = "Albums") }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        items(albums, key = Album::id) { album ->
                            AlbumCard(album = album, onClick = { onOpenAlbum(album.id) })
                        }
                    }
                }
            }
            item { SectionHeader(title = "Morceaux") }
            items(tracks, key = Track::id) { track ->
                TrackRow(track = track, onClick = { viewModel.play(tracks.indexOf(track)) })
            }
        }
    }
}
