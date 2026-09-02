package com.vizuzik.app.player.deezer

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.vizuzik.app.di.ApplicationScope
import com.vizuzik.app.diagnostics.MediaSessionProbe
import com.vizuzik.app.domain.model.MusicSourceType
import com.vizuzik.app.domain.model.PlayerState
import com.vizuzik.app.domain.model.RepeatMode
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.domain.player.PlaybackEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reflète l'état de la session média de l'app Deezer et lui transmet
 * play/pause/next/prev/seek — on ne possède pas ce flux audio, donc pas d'EQ
 * ni de visualiseur possibles dessus (masqués côté UI quand cette source est
 * active). La gestion de file reste minimale : le seul déclenchement fiable,
 * validé en direct, est de lancer un album/playlist entier via
 * [com.vizuzik.app.data.remote.deezer.DeezerPlaybackLauncher], pas de piloter
 * la file de Deezer morceau par morceau — les méthodes de file de
 * [MusicPlayer] sont donc des no-op ici plutôt qu'une simulation trompeuse.
 */
@Singleton
class DeezerRemotePlayer @Inject constructor(
    private val probe: MediaSessionProbe,
    @ApplicationScope private val scope: CoroutineScope,
) : MusicPlayer {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private var controller: MediaController? = null
    private var tickerJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() {
            controller = null
            tickerJob?.cancel()
            _state.update { PlayerState() }
        }
    }

    /**
     * Rattache Vizuzik à la session Deezer active. Retourne false si Deezer
     * n'a aucune session en cours (l'app n'a jamais été ouverte, ou
     * l'autorisation notifications manque) : l'appelant doit alors guider
     * l'utilisateur plutôt que de basculer silencieusement sur une source
     * muette.
     */
    fun attach(): Boolean {
        val active = probe.activeControllers().firstOrNull { it.packageName.contains("deezer", ignoreCase = true) }
            ?: return false
        controller?.unregisterCallback(callback)
        controller = active
        active.registerCallback(callback, handler)
        refresh()
        startTicker()
        return true
    }

    fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        tickerJob?.cancel()
        _state.update { PlayerState() }
    }

    /** Position/état peuvent dériver entre deux callbacks Deezer : un tick régulier les rafraîchit. */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && controller != null) {
                delay(1_000)
                refresh()
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val playback = c.playbackState
        val metadata = c.metadata
        val previousTrack = _state.value.currentTrack
        val track = metadata?.toTrack()

        _state.update {
            it.copy(
                currentTrack = track,
                isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
                isBuffering = playback?.state == PlaybackState.STATE_BUFFERING,
                position = playback?.position?.coerceAtLeast(0L) ?: 0L,
                duration = track?.duration ?: 0L,
                queue = emptyList(),
                queueIndex = if (track != null) 0 else -1,
            )
        }
        if (track?.id != previousTrack?.id) {
            _events.tryEmit(PlaybackEvent.TrackChanged(track))
        }
    }

    private fun MediaMetadata.toTrack(): Track = Track(
        id = "deezer:live",
        sourceType = MusicSourceType.DEEZER,
        title = getString(MediaMetadata.METADATA_KEY_TITLE) ?: "—",
        artist = getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "—",
        artistId = "",
        album = getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "—",
        albumId = "",
        albumArtist = "",
        duration = getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
        uri = "",
        artworkUri = getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: getString(MediaMetadata.METADATA_KEY_ART_URI),
        trackNumber = 0,
        discNumber = 0,
        year = 0,
        genre = null,
    )

    override fun setQueue(tracks: List<Track>, startIndex: Int, playWhenReady: Boolean) {
        // Pas de contrôle fiable de la file Deezer morceau par morceau ; voir
        // DeezerPlaybackLauncher pour lancer un album/playlist entier.
    }

    override fun play() {
        controller?.transportControls?.play()
    }

    override fun pause() {
        controller?.transportControls?.pause()
    }

    override fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    override fun skipToNext() {
        controller?.transportControls?.skipToNext()
    }

    override fun skipToPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    override fun skipToQueueItem(index: Int) {
        // File Deezer non pilotée depuis Vizuzik.
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        // Non exposé de façon fiable par la session Deezer.
    }

    override fun setRepeatMode(mode: RepeatMode) {
        // Non exposé de façon fiable par la session Deezer.
    }

    override fun addToQueue(track: Track, playNext: Boolean) {
        // no-op : voir setQueue.
    }

    override fun moveQueueItem(from: Int, to: Int) {
        // no-op : voir setQueue.
    }

    override fun removeFromQueue(index: Int) {
        // no-op : voir setQueue.
    }

    override fun clearQueue() {
        // no-op : voir setQueue.
    }
}
