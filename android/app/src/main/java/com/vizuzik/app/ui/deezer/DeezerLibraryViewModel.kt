package com.vizuzik.app.ui.deezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.data.remote.deezer.DeezerApiClient
import com.vizuzik.app.data.remote.deezer.DeezerAuthRepository
import com.vizuzik.app.data.remote.deezer.DeezerCollectionItem
import com.vizuzik.app.data.remote.deezer.DeezerLaunchResult
import com.vizuzik.app.data.remote.deezer.DeezerPlaybackLauncher
import com.vizuzik.app.player.MusicPlayerRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeezerLibraryUiState(
    val isLoading: Boolean = true,
    val albums: List<DeezerCollectionItem> = emptyList(),
    val playlists: List<DeezerCollectionItem> = emptyList(),
    val error: String? = null,
    val lastLaunchMessage: String? = null,
)

@HiltViewModel
class DeezerLibraryViewModel @Inject constructor(
    private val authRepository: DeezerAuthRepository,
    private val apiClient: DeezerApiClient,
    private val playbackLauncher: DeezerPlaybackLauncher,
    private val musicPlayerRouter: MusicPlayerRouter,
) : ViewModel() {

    private val _state = MutableStateFlow(DeezerLibraryUiState())
    val state: StateFlow<DeezerLibraryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val token = authRepository.accessToken.first()
            if (token.isNullOrBlank()) {
                _state.update { it.copy(isLoading = false, error = "Non connecté à Deezer.") }
                return@launch
            }
            runCatching {
                val albums = apiClient.fetchAlbums(token)
                val playlists = apiClient.fetchPlaylists(token)
                albums to playlists
            }.onSuccess { (albums, playlists) ->
                _state.update { it.copy(isLoading = false, albums = albums, playlists = playlists) }
            }.onFailure { error ->
                _state.update { it.copy(isLoading = false, error = error.message ?: "Échec du chargement de la bibliothèque Deezer.") }
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
