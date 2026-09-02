package com.vizuzik.app.ui.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.ui.components.ArtworkImage
import com.vizuzik.app.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(onBack: () -> Unit, viewModel: AlbumDetailViewModel = hiltViewModel()) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val first = tracks.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(first?.album.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ArtworkImage(uri = first?.artworkUri, modifier = Modifier.size(180.dp))
                    Text(
                        text = first?.album.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = first?.albumArtist?.ifBlank { first.artist } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { viewModel.play(0) }, modifier = Modifier.padding(top = 16.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text(text = "Lecture", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            items(tracks, key = Track::id) { track ->
                TrackRow(
                    track = track,
                    subtitle = "${track.trackNumber.takeIf { it > 0 } ?: ""}".let { if (it.isBlank()) track.artist else "$it · ${track.artist}" },
                    onClick = { viewModel.play(tracks.indexOf(track)) },
                )
            }
        }
    }
}
