package com.vizuzik.app.ui.deezer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.data.remote.deezer.DeezerCollectionItem
import com.vizuzik.app.ui.components.ArtworkImage
import com.vizuzik.app.ui.components.EmptyState
import com.vizuzik.app.ui.components.LoadingState
import com.vizuzik.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerLibraryScreen(onBack: () -> Unit, viewModel: DeezerLibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.lastLaunchMessage) {
        state.lastLaunchMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissLaunchMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bibliothèque Deezer") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) } },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Actualiser")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            state.error != null -> EmptyState(
                title = "Impossible de charger ta bibliothèque",
                body = state.error.orEmpty(),
                modifier = Modifier.padding(padding),
            )
            state.albums.isEmpty() && state.playlists.isEmpty() -> EmptyState(
                title = "Aucun album ni playlist",
                body = "Ajoute des albums ou crée des playlists dans l'app Deezer, puis actualise ici.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(modifier = Modifier.padding(padding)) {
                if (state.albums.isNotEmpty()) {
                    item { SectionHeader(title = "Mes albums") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.albums, key = { "album-${it.id}" }) { album ->
                                DeezerCollectionCard(item = album, onClick = { viewModel.launch(album) })
                            }
                        }
                    }
                }
                if (state.playlists.isNotEmpty()) {
                    item { SectionHeader(title = "Mes playlists") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.playlists, key = { "playlist-${it.id}" }) { playlist ->
                                DeezerCollectionCard(item = playlist, onClick = { viewModel.launch(playlist) })
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Toucher un album ou une playlist l'envoie directement à l'app Deezer, " +
                            "qui doit être ouverte au moins une fois auparavant.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeezerCollectionCard(item: DeezerCollectionItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        ArtworkImage(uri = item.artworkUrl, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
