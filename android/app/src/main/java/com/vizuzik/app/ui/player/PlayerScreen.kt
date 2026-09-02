package com.vizuzik.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.domain.model.RepeatMode
import com.vizuzik.app.theme.allThemes
import com.vizuzik.app.ui.components.ArtworkImage
import com.vizuzik.app.ui.components.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenVisualizer: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val themeId by viewModel.themeId.collectAsStateWithLifecycle()
    val isDeezerActive by viewModel.isDeezerActive.collectAsStateWithLifecycle()
    var skinMenuOpen by remember { mutableStateOf(false) }
    val track = state.currentTrack

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lecture en cours") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ExpandMore, contentDescription = "Réduire") }
                },
                actions = {
                    IconButton(onClick = { skinMenuOpen = true }) {
                        Icon(Icons.Filled.Palette, contentDescription = "Changer de skin")
                    }
                    DropdownMenu(expanded = skinMenuOpen, onDismissRequest = { skinMenuOpen = false }) {
                        allThemes.forEach { theme ->
                            DropdownMenuItem(
                                text = { Text(theme.name) },
                                onClick = { viewModel.setTheme(theme.id); skinMenuOpen = false },
                                trailingIcon = { if (theme.id == themeId) Text("✓") },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (track == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Aucun morceau en cours")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ArtworkImage(
                uri = track.artworkUri,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )

            Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text(text = track.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = track.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PlaybackProgress(positionMs = state.position, durationMs = state.duration, onSeek = viewModel::seekTo)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isDeezerActive) {
                    IconButton(onClick = viewModel::toggleShuffle) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Lecture aléatoire",
                            tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = viewModel::skipToPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Précédent", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                        modifier = Modifier.size(56.dp),
                    )
                }
                IconButton(onClick = viewModel::skipToNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Suivant", modifier = Modifier.size(36.dp))
                }
                if (!isDeezerActive) {
                    IconButton(onClick = viewModel::cycleRepeatMode) {
                        Icon(
                            imageVector = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Répéter",
                            tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (isDeezerActive) {
                // EQ/visualiseur/file n'ont pas de sens ici : on ne possède pas le
                // flux audio de Deezer, et sa file n'est pas pilotable depuis Vizuzik.
                Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Lecture pilotée depuis l'app Deezer — EQ, visualiseur et file d'attente indisponibles ici.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = viewModel::returnToLocalPlayback) {
                        Text("Revenir à la lecture locale")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = onOpenEqualizer) {
                        Icon(Icons.Filled.Equalizer, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("EQ")
                    }
                    TextButton(onClick = onOpenVisualizer) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("VIS")
                    }
                    TextButton(onClick = onOpenQueue) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("File d'attente")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackProgress(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragPosition by remember { mutableFloatStateOf(-1f) }
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val sliderValue = if (dragPosition >= 0f) dragPosition else progress

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Slider(
            value = sliderValue,
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                if (durationMs > 0) onSeek((dragPosition * durationMs).toLong())
                dragPosition = -1f
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(if (dragPosition >= 0f) (dragPosition * durationMs).toLong() else positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}
