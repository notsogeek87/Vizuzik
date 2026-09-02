package com.vizuzik.app.testutil

import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.Artist
import com.vizuzik.app.domain.model.LibraryScanState
import com.vizuzik.app.domain.model.SearchResult
import com.vizuzik.app.domain.model.SortOrder
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.repository.MusicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Double minimal : seule [recordPlayed] est utilisée par [com.vizuzik.app.domain.usecase.PlayTracksUseCase]. */
class FakeMusicRepository : MusicRepository {

    private val _scanState = MutableStateFlow<LibraryScanState>(LibraryScanState.Idle)
    override val scanState = _scanState.asStateFlow()

    val recordedTracks = mutableListOf<Track>()

    override fun observeTracks(sort: SortOrder): Flow<List<Track>> = error("not needed for this test")
    override fun observeAlbums(): Flow<List<Album>> = error("not needed for this test")
    override fun observeArtists(): Flow<List<Artist>> = error("not needed for this test")
    override fun observeRecentlyAddedAlbums(limit: Int): Flow<List<Album>> = error("not needed for this test")
    override fun observeRecentlyPlayed(limit: Int): Flow<List<Track>> = error("not needed for this test")
    override suspend fun getAlbumTracks(albumId: String): List<Track> = error("not needed for this test")
    override suspend fun getArtistAlbums(artistId: String): List<Album> = error("not needed for this test")
    override suspend fun getArtistTracks(artistId: String): List<Track> = error("not needed for this test")
    override suspend fun getTrack(trackId: String): Track? = error("not needed for this test")
    override suspend fun search(query: String): SearchResult = error("not needed for this test")
    override suspend fun refreshLibrary(force: Boolean) = error("not needed for this test")

    override suspend fun recordPlayed(track: Track) {
        recordedTracks += track
    }
}
