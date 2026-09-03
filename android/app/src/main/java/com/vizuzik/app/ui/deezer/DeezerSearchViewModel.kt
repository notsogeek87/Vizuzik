package com.vizuzik.app.ui.deezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.data.remote.deezer.DeezerApiClient
import com.vizuzik.app.data.remote.deezer.DeezerCollectionItem
import com.vizuzik.app.data.remote.deezer.DeezerLaunchResult
import com.vizuzik.app.data.remote.deezer.DeezerPlaybackLauncher
import com.vizuzik.app.player.MusicPlayerRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeezerSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val albums: List<DeezerCollectionItem> = emptyList(),
    val playlists: List<DeezerCollectionItem> = emptyList(),
    val error: String? = null,
    val lastLaunchMessage: String? = null,
)

/**
 * Recherche dans le catalogue public Deezer (pas de connexion requise, voir
 * [DeezerApiClient]) puis lance l'album/playlist choisi sur la session média
 * Deezer active — c'est tout ce dont on a besoin : lancer un album/une
 * playlist qu'on a déjà sur Deezer, pas choisir un morceau précis.
 */
@HiltViewModel
class DeezerSearchViewModel @Inject constructor(
    private val apiClient: DeezerApiClient,
    private val playbackLauncher: DeezerPlaybackLauncher,
    private val musicPlayerRouter: MusicPlayerRouter,
) : ViewModel() {

    private val _state = MutableStateFlow(DeezerSearchUiState())
    val state: StateFlow<DeezerSearchUiState> = _state.asStateFlow()

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isSearching = true, hasSearched = true, error = null) }
            runCatching {
                val albums = apiClient.searchAlbums(query)
                val playlists = apiClient.searchPlaylists(query)
                albums to playlists
            }.onSuccess { (albums, playlists) ->
                _state.update { it.copy(isSearching = false, albums = albums, playlists = playlists) }
            }.onFailure { error ->
                _state.update { it.copy(isSearching = false, error = error.message ?: "Échec de la recherche Deezer.") }
            }
        }
    }

    fun launch(item: DeezerCollectionItem) {
        val result = playbackLauncher.launch(item.searchQuery)
        // Bascule aussi le lecteur Vizuzik (mini-player, écran plein écran) sur
        // Deezer : sans ça, l'utilisateur voit sa lecture démarrer dans Deezer
        // mais rien ne change dans Vizuzik, alors que c'est justement le but
        // (un lecteur skinné qui reflète Deezer).
        if (result == DeezerLaunchResult.Started) musicPlayerRouter.activateDeezer()
        val message = when (result) {
            DeezerLaunchResult.Started -> "« ${item.title} » envoyé à Deezer."
            DeezerLaunchResult.DeezerNotRunning ->
                "Ouvre d'abord l'app Deezer et lance n'importe quel morceau, puis réessaie."
            DeezerLaunchResult.NotificationAccessMissing ->
                "Autorisation manquante : active l'accès aux notifications dans Réglages → Sonde Deezer."
        }
        _state.update { it.copy(lastLaunchMessage = message) }
    }

    fun dismissLaunchMessage() {
        _state.update { it.copy(lastLaunchMessage = null) }
    }
}
