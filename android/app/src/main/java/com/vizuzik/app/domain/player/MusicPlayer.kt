package com.vizuzik.app.domain.player

import com.vizuzik.app.domain.model.PlayerState
import com.vizuzik.app.domain.model.RepeatMode
import com.vizuzik.app.domain.model.Track
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction du moteur de lecture. [com.vizuzik.app.player.MusicPlayerRouter]
 * est l'implémentation injectée dans toute l'UI : elle délègue à
 * [com.vizuzik.app.player.Media3MusicPlayer] (local, Media3/ExoPlayer) ou à
 * [com.vizuzik.app.player.deezer.DeezerRemotePlayer] (télécommande de la
 * session média Deezer) selon la source active. L'UI ne dépend jamais
 * d'ExoPlayer ni de Deezer directement, uniquement de cette interface.
 */
interface MusicPlayer {
    val state: StateFlow<PlayerState>
    val events: SharedFlow<PlaybackEvent>

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, playWhenReady: Boolean = true)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipToNext()
    fun skipToPrevious()
    fun skipToQueueItem(index: Int)
    fun setShuffleEnabled(enabled: Boolean)
    fun setRepeatMode(mode: RepeatMode)

    fun addToQueue(track: Track, playNext: Boolean = false)
    fun moveQueueItem(from: Int, to: Int)
    fun removeFromQueue(index: Int)
    fun clearQueue()
}
