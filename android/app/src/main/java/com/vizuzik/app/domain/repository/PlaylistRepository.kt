package com.vizuzik.app.domain.repository

import com.vizuzik.app.domain.model.Playlist
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observePlaylist(id: Long): Flow<Playlist?>

    suspend fun createPlaylist(name: String): Long
    suspend fun renamePlaylist(id: Long, name: String)
    suspend fun deletePlaylist(id: Long)

    suspend fun addTrack(playlistId: Long, trackId: String)
    suspend fun removeTrack(playlistId: Long, trackId: String)
    suspend fun reorderTracks(playlistId: Long, orderedTrackIds: List<String>)
}
