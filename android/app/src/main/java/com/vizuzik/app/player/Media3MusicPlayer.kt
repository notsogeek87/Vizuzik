package com.vizuzik.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.vizuzik.app.domain.model.PlayerState
import com.vizuzik.app.domain.model.QueueItem
import com.vizuzik.app.domain.model.RepeatMode
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.domain.player.PlaybackEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation de [MusicPlayer] au-dessus de Media3. Ne détient jamais
 * l'[androidx.media3.exoplayer.ExoPlayer] lui-même — celui-ci vit dans
 * [PlaybackService] pour la lecture en arrière-plan — mais un
 * [MediaController] qui s'y connecte. C'est le seul fichier hors service qui
 * connaît Media3 ; le reste de l'app ne voit que [MusicPlayer].
 */
@Singleton
class Media3MusicPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : MusicPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerState())
    override val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    private var controller: MediaController? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

    /** Copie locale de la file, dans l'ordre d'insertion (indépendante du mode aléatoire). */
    private var queueTracks: List<Track> = emptyList()
    private var progressJob: Job? = null

    init {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            {
                val connected = future.get()
                controller = connected
                attachListener(connected)
                pendingActions.forEach { it(connected) }
                pendingActions.clear()
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun withController(action: (MediaController) -> Unit) {
        val current = controller
        if (current != null) action(current) else pendingActions += action
    }

    private fun attachListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                _events.tryEmit(if (isPlaying) PlaybackEvent.PlaybackStarted else PlaybackEvent.PlaybackPaused)
                if (isPlaying) startProgressLoop(controller) else progressJob?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update {
                    it.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        duration = controller.duration.coerceAtLeast(0L),
                    )
                }
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    _events.tryEmit(PlaybackEvent.PlaybackStopped)
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                refreshQueueState(controller)
                _events.tryEmit(PlaybackEvent.TrackChanged(currentTrack(controller)))
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                refreshQueueState(controller)
                _events.tryEmit(PlaybackEvent.QueueChanged)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _state.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _state.update { it.copy(repeatMode = repeatMode.toDomainRepeatMode()) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _events.tryEmit(PlaybackEvent.PlaybackError(error.message ?: "Erreur de lecture"))
            }
        })
        _state.update {
            it.copy(
                isPlaying = controller.isPlaying,
                shuffleEnabled = controller.shuffleModeEnabled,
                repeatMode = controller.repeatMode.toDomainRepeatMode(),
                duration = controller.duration.coerceAtLeast(0L),
            )
        }
        if (controller.isPlaying) startProgressLoop(controller)
        refreshQueueState(controller)
    }

    private fun startProgressLoop(controller: MediaController) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val position = controller.currentPosition.coerceAtLeast(0L)
                _state.update { it.copy(position = position) }
                _events.tryEmit(PlaybackEvent.PositionChanged(position))
                delay(500)
            }
        }
    }

    private fun currentTrack(controller: MediaController): Track? {
        val index = controller.currentMediaItemIndex
        return queueTracks.getOrNull(index)
    }

    private fun refreshQueueState(controller: MediaController) {
        val index = controller.currentMediaItemIndex.takeIf { it in queueTracks.indices } ?: -1
        _state.update {
            it.copy(
                queue = queueTracks.mapIndexed { i, track -> QueueItem(track, i) },
                queueIndex = index,
                currentTrack = queueTracks.getOrNull(index),
                position = controller.currentPosition.coerceAtLeast(0L),
                duration = controller.duration.coerceAtLeast(0L),
            )
        }
    }

    override fun setQueue(tracks: List<Track>, startIndex: Int, playWhenReady: Boolean) {
        queueTracks = tracks
        val safeIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        _state.update {
            it.copy(
                queue = tracks.mapIndexed { i, track -> QueueItem(track, i) },
                queueIndex = if (tracks.isEmpty()) -1 else safeIndex,
                currentTrack = tracks.getOrNull(safeIndex),
                position = 0L,
            )
        }
        withController { controller ->
            controller.setMediaItems(tracks.map { it.toMediaItem() }, safeIndex, 0L)
            controller.playWhenReady = playWhenReady
            controller.prepare()
        }
    }

    override fun play() = withController { it.play() }

    override fun pause() = withController { it.pause() }

    override fun togglePlayPause() = withController { if (it.isPlaying) it.pause() else it.play() }

    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    override fun skipToNext() = withController { it.seekToNextMediaItem() }

    override fun skipToPrevious() = withController { controller ->
        if (controller.currentPosition > 3_000L) controller.seekTo(0L) else controller.seekToPreviousMediaItem()
    }

    override fun skipToQueueItem(index: Int) = withController { it.seekTo(index, 0L) }

    override fun setShuffleEnabled(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    override fun setRepeatMode(mode: RepeatMode) = withController { it.repeatMode = mode.toPlayerRepeatMode() }

    override fun addToQueue(track: Track, playNext: Boolean) {
        val current = controller?.currentMediaItemIndex ?: (queueTracks.size - 1)
        val insertAt = if (playNext) (current + 1).coerceIn(0, queueTracks.size) else queueTracks.size
        queueTracks = queueTracks.toMutableList().apply { add(insertAt, track) }
        withController { controller ->
            controller.addMediaItem(insertAt.coerceIn(0, controller.mediaItemCount), track.toMediaItem())
            refreshQueueState(controller)
        }
    }

    override fun moveQueueItem(from: Int, to: Int) {
        if (from !in queueTracks.indices || to !in queueTracks.indices) return
        queueTracks = queueTracks.toMutableList().apply { add(to, removeAt(from)) }
        withController { controller ->
            controller.moveMediaItem(from, to)
            refreshQueueState(controller)
        }
    }

    override fun removeFromQueue(index: Int) {
        if (index !in queueTracks.indices) return
        queueTracks = queueTracks.toMutableList().apply { removeAt(index) }
        withController { controller ->
            controller.removeMediaItem(index)
            refreshQueueState(controller)
        }
    }

    override fun clearQueue() {
        queueTracks = emptyList()
        _state.update { it.copy(queue = emptyList(), queueIndex = -1, currentTrack = null) }
        withController { it.clearMediaItems() }
    }
}
