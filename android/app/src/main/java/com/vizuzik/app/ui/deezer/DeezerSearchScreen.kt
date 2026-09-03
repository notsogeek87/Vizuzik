package com.vizuzik.app.ui.deezer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.data.remote.deezer.DeezerCollectionItem
import com.vizuzik.app.ui.components.ArtworkImage
import com.vizuzik.app.ui.components.EmptyState
import com.vizuzik.app.ui.components.SectionHeader

/**
 * Pas de connexion Deezer requise : la recherche porte sur le catalogue
 * public (voir [com.vizuzik.app.data.remote.deezer.DeezerApiClient]), Deezer
 * n'ayant pas accepté la création d'une app développeur pour ce projet.
 * Toucher un résultat lance l'album/playlist correspondant sur l'app Deezer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerSearchScreen(onBack: () -> Unit, viewModel: DeezerSearchViewModel = hiltViewModel()) {
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
                title = { Text("Lancer sur Deezer") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text("Artiste, album ou playlist") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::search) {
                    Icon(Icons.Filled.Search, contentDescription = "Rechercher")
                }
            }

            when {
                state.isSearching -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                state.error != null -> EmptyState(
                    title = "Recherche impossible",
                    body = state.error.orEmpty(),
                )
                !state.hasSearched -> EmptyState(
                    title = "Cherche un album ou une playlist",
                    body = "Tape un nom d'artiste, d'album ou de playlist que tu as sur Deezer, " +
                        "puis appuie sur la loupe. L'app Deezer doit avoir été ouverte au moins une fois.",
                )
                state.albums.isEmpty() && state.playlists.isEmpty() -> EmptyState(
                    title = "Aucun résultat",
                    body = "Essaie un autre nom d'artiste, d'album ou de playlist.",
                )
                else -> LazyColumn {
                    if (state.albums.isNotEmpty()) {
                        item { SectionHeader(title = "Albums") }
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
                        item { SectionHeader(title = "Playlists") }
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
                            "Toucher un résultat l'envoie directement à l'app Deezer, qui doit être " +
                                "ouverte au moins une fois auparavant.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
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
