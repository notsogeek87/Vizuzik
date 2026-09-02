package com.vizuzik.app.ui.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.domain.model.Playlist
import com.vizuzik.app.domain.repository.PlaylistRepository
import com.vizuzik.app.domain.usecase.PlayTracksUseCase
import com.vizuzik.app.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val playTracksUseCase: PlayTracksUseCase,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle[Destination.PlaylistDetail.ARG])

    val playlist: StateFlow<Playlist?> = playlistRepository.observePlaylist(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun play(startIndex: Int = 0) {
        val tracks = playlist.value?.tracks ?: return
        viewModelScope.launch { playTracksUseCase(tracks, startIndex) }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { playlistRepository.renamePlaylist(playlistId, name.trim()) }
    }

    fun delete() {
        viewModelScope.launch { playlistRepository.deletePlaylist(playlistId) }
    }

    fun removeTrack(trackId: String) {
        viewModelScope.launch { playlistRepository.removeTrack(playlistId, trackId) }
    }

    fun moveTrack(from: Int, to: Int) {
        val tracks = playlist.value?.tracks?.toMutableList() ?: return
        if (from !in tracks.indices || to !in tracks.indices) return
        tracks.add(to, tracks.removeAt(from))
        viewModelScope.launch { playlistRepository.reorderTracks(playlistId, tracks.map { it.id }) }
    }
}
