package com.vizuzik.app.domain.source

import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.Artist
import com.vizuzik.app.domain.model.MusicSourceType
import com.vizuzik.app.domain.model.SearchResult
import com.vizuzik.app.domain.model.Track

/**
 * Abstraction d'une source de musique. [com.vizuzik.app.data.source.LocalMusicSource]
 * est la seule implémentation en V0.1. Une future source Deezer officielle
 * implémenterait cette même interface et s'enregistrerait auprès de
 * [com.vizuzik.app.domain.repository.MusicRepository] sans changement ni du
 * player, ni de l'UI.
 */
interface MusicSource {
    val sourceType: MusicSourceType

    suspend fun getTracks(): List<Track>
    suspend fun getAlbums(): List<Album>
    suspend fun getArtists(): List<Artist>
    suspend fun search(query: String): SearchResult
}
