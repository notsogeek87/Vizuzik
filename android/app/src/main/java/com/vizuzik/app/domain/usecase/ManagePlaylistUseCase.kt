package com.vizuzik.app.domain.usecase

import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.repository.PlaylistRepository
import javax.inject.Inject

/**
 * Façade au-dessus de [PlaylistRepository] pour les opérations d'ajout qui
 * portent sur un [Track] complet plutôt qu'un simple identifiant.
 */
class ManagePlaylistUseCase @Inject constructor(
    private val playlistRepository: PlaylistRepository,
) {
    suspend fun addTracks(playlistId: Long, tracks: List<Track>) {
        tracks.forEach { playlistRepository.addTrack(playlistId, it.id) }
    }

    suspend fun createWithTracks(name: String, tracks: List<Track>): Long {
        val id = playlistRepository.createPlaylist(name)
        addTracks(id, tracks)
        return id
    }
}
