package com.vizuzik.app.domain.repository

import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.Artist
import com.vizuzik.app.domain.model.LibraryScanState
import com.vizuzik.app.domain.model.SearchResult
import com.vizuzik.app.domain.model.SortOrder
import com.vizuzik.app.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Point d'accès unique à la bibliothèque musicale, agrégeant une ou plusieurs
 * [com.vizuzik.app.domain.source.MusicSource] (uniquement la source locale en
 * V0.1). Met en cache les métadonnées pour éviter un rescan complet à chaque
 * ouverture de l'application.
 */
interface MusicRepository {
    val scanState: StateFlow<LibraryScanState>

    fun observeTracks(sort: SortOrder = SortOrder.TITLE): Flow<List<Track>>
    fun observeAlbums(): Flow<List<Album>>
    fun observeArtists(): Flow<List<Artist>>
    fun observeRecentlyAddedAlbums(limit: Int = 10): Flow<List<Album>>
    fun observeRecentlyPlayed(limit: Int = 10): Flow<List<Track>>

    suspend fun getAlbumTracks(albumId: String): List<Track>
    suspend fun getArtistAlbums(artistId: String): List<Album>
    suspend fun getArtistTracks(artistId: String): List<Track>
    suspend fun getTrack(trackId: String): Track?
    suspend fun search(query: String): SearchResult

    /** Rescanne la source locale. [force] ignore le cache et retraite tous les fichiers. */
    suspend fun refreshLibrary(force: Boolean = false)
    suspend fun recordPlayed(track: Track)
}
