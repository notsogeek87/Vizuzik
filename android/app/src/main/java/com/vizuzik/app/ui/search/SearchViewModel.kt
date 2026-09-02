package com.vizuzik.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.domain.model.SearchResult
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.repository.MusicRepository
import com.vizuzik.app.domain.usecase.PlayTracksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playTracksUseCase: PlayTracksUseCase,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val result: StateFlow<SearchResult> = _query
        .debounce(200)
        .mapLatest { query -> if (query.isBlank()) SearchResult() else musicRepository.search(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResult())

    fun setQuery(query: String) {
        _query.value = query
    }

    fun playTrack(track: Track, within: List<Track>) {
        viewModelScope.launch { playTracksUseCase(within, within.indexOf(track).coerceAtLeast(0)) }
    }
}
