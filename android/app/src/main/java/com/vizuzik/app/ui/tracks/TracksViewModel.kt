package com.vizuzik.app.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.domain.library.SearchMatcher
import com.vizuzik.app.domain.model.Playlist
import com.vizuzik.app.domain.model.SortOrder
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.repository.MusicRepository
import com.vizuzik.app.domain.repository.PlaylistRepository
import com.vizuzik.app.domain.usecase.ManagePlaylistUseCase
import com.vizuzik.app.domain.usecase.PlayTracksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TracksViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playTracksUseCase: PlayTracksUseCase,
    private val playlistRepository: PlaylistRepository,
    private val managePlaylistUseCase: ManagePlaylistUseCase,
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = playlistRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sortOrder = MutableStateFlow(SortOrder.TITLE)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val tracks: StateFlow<List<Track>> = combine(
        _sortOrder.flatMapLatest { musicRepository.observeTracks(it) },
        _query,
    ) { list, query ->
        if (query.isBlank()) {
            list
        } else {
            list.filter {
                SearchMatcher.matches(it.title, query) ||
                    SearchMatcher.matches(it.artist, query) ||
                    SearchMatcher.matches(it.album, query)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun playTrack(track: Track) {
        viewModelScope.launch {
            val list = tracks.value
            playTracksUseCase(list, list.indexOf(track).coerceAtLeast(0))
        }
    }

    fun addToPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { managePlaylistUseCase.addTracks(playlistId, listOf(track)) }
    }

    fun createPlaylistWithTrack(name: String, track: Track) {
        if (name.isBlank()) return
        viewModelScope.launch { managePlaylistUseCase.createWithTracks(name.trim(), listOf(track)) }
    }
}
