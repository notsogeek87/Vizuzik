package com.vizuzik.app.player

import com.vizuzik.app.di.ApplicationScope
import com.vizuzik.app.domain.model.PlayerState
import com.vizuzik.app.domain.model.RepeatMode
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.domain.player.PlaybackEvent
import com.vizuzik.app.player.deezer.DeezerRemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unique [MusicPlayer] injecté dans toute l'UI (voir [com.vizuzik.app.di.PlayerModule]) :
 * délègue à [Media3MusicPlayer] ou [DeezerRemotePlayer] selon
 * [PlaybackSourceController.activeSource], sans qu'aucun écran n'ait à
 * connaître la distinction — mini-player, lecteur plein écran, file, etc.
 * fonctionnent donc tels quels pour les deux sources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MusicPlayerRouter @Inject constructor(
    private val sourceController: PlaybackSourceController,
    private val localPlayer: Media3MusicPlayer,
    private val deezerPlayer: DeezerRemotePlayer,
    @ApplicationScope scope: CoroutineScope,
) : MusicPlayer {

    private fun delegateFor(source: PlaybackSource): MusicPlayer = when (source) {
        PlaybackSource.LOCAL -> localPlayer
        PlaybackSource.DEEZER -> deezerPlayer
    }

    private val active: MusicPlayer get() = delegateFor(sourceController.activeSource.value)

    override val state: StateFlow<PlayerState> = sourceController.activeSource
        .flatMapLatest { delegateFor(it).state }
        .stateIn(scope, SharingStarted.Eagerly, localPlayer.state.value)

    override val events: SharedFlow<PlaybackEvent> = sourceController.activeSource
        .flatMapLatest { delegateFor(it).events }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override fun setQueue(tracks: List<Track>, startIndex: Int, playWhenReady: Boolean) =
        active.setQueue(tracks, startIndex, playWhenReady)

    override fun play() = active.play()
    override fun pause() = active.pause()
    override fun togglePlayPause() = active.togglePlayPause()
    override fun seekTo(positionMs: Long) = active.seekTo(positionMs)
    override fun skipToNext() = active.skipToNext()
    override fun skipToPrevious() = active.skipToPrevious()
    override fun skipToQueueItem(index: Int) = active.skipToQueueItem(index)
    override fun setShuffleEnabled(enabled: Boolean) = active.setShuffleEnabled(enabled)
    override fun setRepeatMode(mode: RepeatMode) = active.setRepeatMode(mode)
    override fun addToQueue(track: Track, playNext: Boolean) = active.addToQueue(track, playNext)
    override fun moveQueueItem(from: Int, to: Int) = active.moveQueueItem(from, to)
    override fun removeFromQueue(index: Int) = active.removeFromQueue(index)
    override fun clearQueue() = active.clearQueue()

    /**
     * Bascule vers Deezer : met en pause la lecture locale (les deux sources
     * ne doivent pas jouer en même temps) et rattache [DeezerRemotePlayer] à
     * la session Deezer active. Retourne false si Deezer n'a aucune session
     * en cours — l'appelant doit alors inviter l'utilisateur à ouvrir Deezer
     * d'abord plutôt que de basculer silencieusement sur une source muette.
     */
    fun activateDeezer(): Boolean {
        if (!deezerPlayer.attach()) return false
        if (sourceController.activeSource.value == PlaybackSource.LOCAL) localPlayer.pause()
        sourceController.activate(PlaybackSource.DEEZER)
        return true
    }

    fun activateLocal() {
        deezerPlayer.detach()
        sourceController.activate(PlaybackSource.LOCAL)
    }
}
