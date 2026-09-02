package com.vizuzik.app.data.source

import com.vizuzik.app.data.local.mediastore.MediaStoreDataSource
import com.vizuzik.app.domain.library.LibraryAggregator
import com.vizuzik.app.domain.library.SearchMatcher
import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.Artist
import com.vizuzik.app.domain.model.MusicSourceType
import com.vizuzik.app.domain.model.SearchResult
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.source.MusicSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation V0.1 de [MusicSource] : fichiers audio locaux via
 * [MediaStoreDataSource]. Aucune dépendance Deezer.
 */
@Singleton
class LocalMusicSource @Inject constructor(
    private val mediaStoreDataSource: MediaStoreDataSource,
) : MusicSource {

    override val sourceType: MusicSourceType = MusicSourceType.LOCAL

    override suspend fun getTracks(): List<Track> = mediaStoreDataSource.queryTracks()

    override suspend fun getAlbums(): List<Album> = LibraryAggregator.aggregateAlbums(getTracks())

    override suspend fun getArtists(): List<Artist> = LibraryAggregator.aggregateArtists(getTracks())

    override suspend fun search(query: String): SearchResult {
        val tracks = getTracks()
        return SearchMatcher.search(tracks, LibraryAggregator.aggregateAlbums(tracks), LibraryAggregator.aggregateArtists(tracks), query)
    }
}
