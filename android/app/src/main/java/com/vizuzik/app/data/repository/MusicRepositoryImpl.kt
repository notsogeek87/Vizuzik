package com.vizuzik.app.data.repository

import com.vizuzik.app.data.local.db.dao.LibraryDao
import com.vizuzik.app.data.local.db.dao.RecentlyPlayedDao
import com.vizuzik.app.data.local.db.entity.toDomain
import com.vizuzik.app.data.local.db.entity.toEntity
import com.vizuzik.app.domain.library.LibraryAggregator
import com.vizuzik.app.domain.library.SearchMatcher
import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.Artist
import com.vizuzik.app.domain.model.LibraryScanState
import com.vizuzik.app.domain.model.SearchResult
import com.vizuzik.app.domain.model.SortOrder
import com.vizuzik.app.domain.model.Track
import com.vizuzik.app.domain.repository.MusicRepository
import com.vizuzik.app.domain.source.MusicSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val localMusicSource: MusicSource,
    private val libraryDao: LibraryDao,
    private val recentlyPlayedDao: RecentlyPlayedDao,
) : MusicRepository {

    private val _scanState = MutableStateFlow<LibraryScanState>(LibraryScanState.Idle)
    override val scanState = _scanState.asStateFlow()

    override fun observeTracks(sort: SortOrder): Flow<List<Track>> =
        libraryDao.observeAllTracks().map { entities ->
            entities.map { it.toDomain() }.sortedWith(comparatorFor(sort))
        }

    override fun observeAlbums(): Flow<List<Album>> =
        libraryDao.observeAllTracks().map { entities -> LibraryAggregator.aggregateAlbums(entities.map { it.toDomain() }) }

    override fun observeArtists(): Flow<List<Artist>> =
        libraryDao.observeAllTracks().map { entities -> LibraryAggregator.aggregateArtists(entities.map { it.toDomain() }) }

    override fun observeRecentlyAddedAlbums(limit: Int): Flow<List<Album>> =
        libraryDao.observeAllTracks().map { entities ->
            val tracks = entities.map { it.toDomain() }
            val latestByAlbum = tracks.groupBy { it.albumId }.mapValues { (_, v) -> v.maxOf { it.dateAdded } }
            LibraryAggregator.aggregateAlbums(tracks)
                .sortedByDescending { latestByAlbum[it.id] ?: 0L }
                .take(limit)
        }

    override fun observeRecentlyPlayed(limit: Int): Flow<List<Track>> =
        recentlyPlayedDao.observeRecentlyPlayed(limit).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getAlbumTracks(albumId: String): List<Track> =
        libraryDao.getAllTracksOnce().map { it.toDomain() }
            .filter { it.albumId == albumId }
            .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))

    override suspend fun getArtistAlbums(artistId: String): List<Album> =
        LibraryAggregator.aggregateAlbums(libraryDao.getAllTracksOnce().map { it.toDomain() }.filter { it.artistId == artistId })

    override suspend fun getArtistTracks(artistId: String): List<Track> =
        libraryDao.getAllTracksOnce().map { it.toDomain() }
            .filter { it.artistId == artistId }
            .sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }))

    override suspend fun getTrack(trackId: String): Track? = libraryDao.getTrackById(trackId)?.toDomain()

    override suspend fun search(query: String): SearchResult {
        val tracks = libraryDao.getAllTracksOnce().map { it.toDomain() }
        val albums = LibraryAggregator.aggregateAlbums(tracks)
        val artists = LibraryAggregator.aggregateArtists(tracks)
        return SearchMatcher.search(tracks, albums, artists, query)
    }

    override suspend fun refreshLibrary(force: Boolean) {
        if (!force && libraryDao.count() > 0) return
        _scanState.value = LibraryScanState.Scanning
        runCatching {
            val tracks = localMusicSource.getTracks()
            libraryDao.replaceAll(tracks.map { it.toEntity() })
            recentlyPlayedDao.pruneOrphans()
        }.onFailure { error ->
            _scanState.value = LibraryScanState.Error(error.message ?: "Erreur d'analyse de la bibliothèque")
            return
        }
        _scanState.value = LibraryScanState.Idle
    }

    override suspend fun recordPlayed(track: Track) {
        recentlyPlayedDao.recordPlayed(track.id, System.currentTimeMillis())
    }

    /** Tri via [Collator] : "Édith" se classe bien avec "Edith", pas après "Z". */
    private fun comparatorFor(order: SortOrder): Comparator<Track> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val byTitle = Comparator<Track> { a, b -> collator.compare(a.title, b.title) }
        val byArtist = Comparator<Track> { a, b -> collator.compare(a.artist, b.artist) }
        val byAlbum = Comparator<Track> { a, b -> collator.compare(a.album, b.album) }
        val byPosition = compareBy<Track>({ it.discNumber }, { it.trackNumber })
        return when (order) {
            SortOrder.TITLE -> byTitle
            SortOrder.ARTIST -> byArtist.then(byAlbum).then(byPosition)
            SortOrder.ALBUM -> byAlbum.then(byPosition)
        }
    }
}
