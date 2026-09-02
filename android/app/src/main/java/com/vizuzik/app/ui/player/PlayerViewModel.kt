package com.vizuzik.app.ui.player

import androidx.lifecycle.ViewModel
import com.vizuzik.app.domain.model.PlayerState
import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.player.MusicPlayerRouter
import com.vizuzik.app.player.PlaybackSource
import com.vizuzik.app.player.PlaybackSourceController
import com.vizuzik.app.theme.ThemeController
import com.vizuzik.app.theme.ThemeId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import javax.inject.Inject

/**
 * Fine couche au-dessus de [MusicPlayer] (lui-même un simple wrapper autour
 * du [androidx.media3.session.MediaController] singleton, ou de la session
 * média Deezer selon la source active) : partagée par le mini-player,
 * l'écran lecteur et la file d'attente, qui affichent donc toujours le même
 * état sans se coordonner explicitement.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val themeController: ThemeController,
    private val sourceController: PlaybackSourceController,
    private val musicPlayerRouter: MusicPlayerRouter,
) : ViewModel() {

    val state: StateFlow<PlayerState> = musicPlayer.state
    val themeId: StateFlow<ThemeId> = themeController.themeId

    /** EQ/visualiseur et gestion fine de la file n'ont de sens que sur la lecture locale. */
    val isDeezerActive: StateFlow<Boolean> = sourceController.activeSource
        .map { it == PlaybackSource.DEEZER }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun returnToLocalPlayback() = musicPlayerRouter.activateLocal()

    fun setTheme(id: ThemeId) = themeController.setTheme(id)

    fun togglePlayPause() = musicPlayer.togglePlayPause()
    fun skipToNext() = musicPlayer.skipToNext()
    fun skipToPrevious() = musicPlayer.skipToPrevious()
    fun seekTo(positionMs: Long) = musicPlayer.seekTo(positionMs)
    fun toggleShuffle() = musicPlayer.setShuffleEnabled(!state.value.shuffleEnabled)
    fun cycleRepeatMode() = musicPlayer.setRepeatMode(state.value.repeatMode.next())
    fun playQueueItem(index: Int) = musicPlayer.skipToQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = musicPlayer.moveQueueItem(from, to)
    fun removeFromQueue(index: Int) = musicPlayer.removeFromQueue(index)
    fun clearQueue() = musicPlayer.clearQueue()
}
