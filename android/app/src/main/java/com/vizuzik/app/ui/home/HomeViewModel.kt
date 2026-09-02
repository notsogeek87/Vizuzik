package com.vizuzik.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.LibraryScanState
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.repository.MusicRepository
import com.vizuzik.app.domain.usecase.PlayTracksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playTracksUseCase: PlayTracksUseCase,
) : ViewModel() {

    val scanState: StateFlow<LibraryScanState> = musicRepository.scanState

    val recentAlbums: StateFlow<List<Album>> = musicRepository.observeRecentlyAddedAlbums(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyPlayed: StateFlow<List<Track>> = musicRepository.observeRecentlyPlayed(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val libraryIsEmpty: StateFlow<Boolean> = musicRepository.observeTracks()
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Ne scanne réellement que si la bibliothèque n'a encore jamais été
        // analysée (voir MusicRepositoryImpl.refreshLibrary) : pas de rescan
        // à chaque ouverture de l'app.
        viewModelScope.launch { musicRepository.refreshLibrary(force = false) }
    }

    fun refreshLibrary() {
        viewModelScope.launch { musicRepository.refreshLibrary(force = true) }
    }

    fun playAlbum(album: Album) {
        viewModelScope.launch {
            val tracks = musicRepository.getAlbumTracks(album.id)
            playTracksUseCase(tracks)
        }
    }

    fun playTrack(track: Track, within: List<Track>) {
        viewModelScope.launch {
            val startIndex = within.indexOf(track).coerceAtLeast(0)
            playTracksUseCase(within, startIndex)
        }
    }
}
