package com.vizuzik.app.data.repository

import com.vizuzik.app.data.local.db.dao.PlaylistDao
import com.vizuzik.app.data.local.db.entity.PlaylistEntity
import com.vizuzik.app.data.local.db.entity.PlaylistTrackCrossRef
import com.vizuzik.app.data.local.db.entity.toDomain
import com.vizuzik.app.domain.model.Playlist
import com.vizuzik.app.domain.repository.PlaylistRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylists().flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(playlists.map { playlist -> observePlaylist(playlist.id) }) { results ->
                    results.filterNotNull()
                }
            }
        }

    override fun observePlaylist(id: Long): Flow<Playlist?> =
        combine(
            playlistDao.observePlaylist(id),
            playlistDao.observePlaylistTracks(id),
        ) { entity, tracks ->
            entity?.let {
                Playlist(
                    id = it.id,
                    name = it.name,
                    createdAt = it.createdAt,
                    tracks = tracks.map { track -> track.toDomain() },
                )
            }
        }

    override suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    override suspend fun renamePlaylist(id: Long, name: String) = playlistDao.renamePlaylist(id, name)

    override suspend fun deletePlaylist(id: Long) {
        playlistDao.clearPlaylistTracks(id)
        playlistDao.deletePlaylist(id)
    }

    override suspend fun addTrack(playlistId: Long, trackId: String) {
        val position = playlistDao.nextPosition(playlistId)
        playlistDao.insertCrossRef(PlaylistTrackCrossRef(playlistId, trackId, position))
    }

    override suspend fun removeTrack(playlistId: Long, trackId: String) =
        playlistDao.removeCrossRef(playlistId, trackId)

    override suspend fun reorderTracks(playlistId: Long, orderedTrackIds: List<String>) {
        playlistDao.clearPlaylistTracks(playlistId)
        playlistDao.insertCrossRefs(
            orderedTrackIds.mapIndexed { index, trackId -> PlaylistTrackCrossRef(playlistId, trackId, index) }
        )
    }
}
