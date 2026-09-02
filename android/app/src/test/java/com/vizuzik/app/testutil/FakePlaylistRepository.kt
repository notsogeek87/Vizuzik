package com.vizuzik.app.testutil

import com.vizuzik.app.domain.model.Playlist
import com.vizuzik.app.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** Playlist repository entièrement en mémoire, pour tester la logique métier sans Room. */
class FakePlaylistRepository : PlaylistRepository {

    private var nextId = 1L
    private val playlists = MutableStateFlow<List<Playlist>>(emptyList())

    override fun observePlaylists(): Flow<List<Playlist>> = playlists.asStateFlow()

    override fun observePlaylist(id: Long): Flow<Playlist?> = playlists.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun createPlaylist(name: String): Long {
        val id = nextId++
        playlists.value = playlists.value + Playlist(id = id, name = name, createdAt = 0L)
        return id
    }

    override suspend fun renamePlaylist(id: Long, name: String) {
        playlists.value = playlists.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun deletePlaylist(id: Long) {
        playlists.value = playlists.value.filterNot { it.id == id }
    }

    override suspend fun addTrack(playlistId: Long, trackId: String) {
        playlists.value = playlists.value.map { playlist ->
            if (playlist.id != playlistId) return@map playlist
            if (playlist.tracks.any { it.id == trackId }) return@map playlist
            playlist.copy(tracks = playlist.tracks + testTrack(id = trackId))
        }
    }

    override suspend fun removeTrack(playlistId: Long, trackId: String) {
        playlists.value = playlists.value.map { playlist ->
            if (playlist.id != playlistId) playlist else playlist.copy(tracks = playlist.tracks.filterNot { it.id == trackId })
        }
    }

    override suspend fun reorderTracks(playlistId: Long, orderedTrackIds: List<String>) {
        playlists.value = playlists.value.map { playlist ->
            if (playlist.id != playlistId) return@map playlist
            val byId = playlist.tracks.associateBy { it.id }
            playlist.copy(tracks = orderedTrackIds.mapNotNull { byId[it] })
        }
    }
}
