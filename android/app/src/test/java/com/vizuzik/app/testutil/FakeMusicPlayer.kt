package com.vizuzik.app.testutil

import com.vizuzik.app.domain.model.PlayerState
import com.vizuzik.app.domain.model.QueueItem
import com.vizuzik.app.domain.model.RepeatMode
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.domain.player.PlaybackEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Double de test pour [MusicPlayer] : ne pilote aucun lecteur réel, seulement le [PlayerState]. */
class FakeMusicPlayer : MusicPlayer {

    private val _state = MutableStateFlow(PlayerState())
    override val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events = _events.asSharedFlow()

    var lastSetQueueTracks: List<Track>? = null
        private set
    var lastSetQueueStartIndex: Int? = null
        private set

    override fun setQueue(tracks: List<Track>, startIndex: Int, playWhenReady: Boolean) {
        val safeIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        lastSetQueueTracks = tracks
        lastSetQueueStartIndex = startIndex
        _state.update {
            it.copy(
                queue = tracks.mapIndexed { i, track -> QueueItem(track, i) },
                queueIndex = if (tracks.isEmpty()) -1 else safeIndex,
                currentTrack = tracks.getOrNull(safeIndex),
                isPlaying = playWhenReady,
            )
        }
    }

    override fun play() = _state.update { it.copy(isPlaying = true) }
    override fun pause() = _state.update { it.copy(isPlaying = false) }
    override fun togglePlayPause() = _state.update { it.copy(isPlaying = !it.isPlaying) }
    override fun seekTo(positionMs: Long) = _state.update { it.copy(position = positionMs) }
    override fun skipToNext() = Unit
    override fun skipToPrevious() = Unit
    override fun skipToQueueItem(index: Int) = _state.update { it.copy(queueIndex = index) }

    override fun setShuffleEnabled(enabled: Boolean) {
        _state.update { it.copy(shuffleEnabled = enabled) }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        _state.update { it.copy(repeatMode = mode) }
    }

    override fun addToQueue(track: Track, playNext: Boolean) = Unit
    override fun moveQueueItem(from: Int, to: Int) = Unit
    override fun removeFromQueue(index: Int) = Unit
    override fun clearQueue() = _state.update { it.copy(queue = emptyList(), queueIndex = -1, currentTrack = null) }
}
