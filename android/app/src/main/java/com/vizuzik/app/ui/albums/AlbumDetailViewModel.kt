package com.vizuzik.app.ui.albums

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.repository.MusicRepository
import com.vizuzik.app.domain.usecase.PlayTracksUseCase
import com.vizuzik.app.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playTracksUseCase: PlayTracksUseCase,
) : ViewModel() {

    private val albumId: String = checkNotNull(savedStateHandle[Destination.AlbumDetail.ARG])

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    init {
        viewModelScope.launch { _tracks.value = musicRepository.getAlbumTracks(albumId) }
    }

    fun play(startIndex: Int = 0) {
        viewModelScope.launch { playTracksUseCase(_tracks.value, startIndex) }
    }
}
