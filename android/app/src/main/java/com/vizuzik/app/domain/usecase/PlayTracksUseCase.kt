package com.vizuzik.app.domain.usecase

import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.player.MusicPlayer
import com.vizuzik.app.domain.repository.MusicRepository
import javax.inject.Inject

/**
 * Remplace la file d'attente par [tracks], démarre la lecture à [startIndex]
 * et enregistre le morceau dans l'historique "récemment joué".
 */
class PlayTracksUseCase @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val musicRepository: MusicRepository,
) {
    suspend operator fun invoke(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        musicPlayer.setQueue(tracks, startIndex, playWhenReady = true)
        musicRepository.recordPlayed(tracks[startIndex.coerceIn(tracks.indices)])
    }
}
