package com.vizuzik.app.ui.player

import androidx.lifecycle.ViewModel
import com.vizuzik.app.domain.model.PlayerState
import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.theme.ThemeController
import com.vizuzik.app.theme.ThemeId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Fine couche au-dessus de [MusicPlayer] (lui-même un simple wrapper autour
 * du [androidx.media3.session.MediaController] singleton) : partagée par le
 * mini-player, l'écran lecteur et la file d'attente, qui affichent donc
 * toujours le même état sans se coordonner explicitement.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val themeController: ThemeController,
) : ViewModel() {

    val state: StateFlow<PlayerState> = musicPlayer.state
    val themeId: StateFlow<ThemeId> = themeController.themeId

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
