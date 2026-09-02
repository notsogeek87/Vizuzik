package com.vizuzik.app.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class PlaybackSource { LOCAL, DEEZER }

/**
 * Quelle source [com.vizuzik.app.domain.player.MusicPlayer] pilote
 * actuellement l'UI (mini-player, lecteur plein écran...). [MusicPlayerRouter]
 * lit cet état pour savoir vers quelle implémentation déléguer, sans que le
 * reste de l'app n'ait jamais besoin de connaître la distinction.
 */
@Singleton
class PlaybackSourceController @Inject constructor() {
    private val _activeSource = MutableStateFlow(PlaybackSource.LOCAL)
    val activeSource: StateFlow<PlaybackSource> = _activeSource.asStateFlow()

    fun activate(source: PlaybackSource) {
        _activeSource.value = source
    }
}
