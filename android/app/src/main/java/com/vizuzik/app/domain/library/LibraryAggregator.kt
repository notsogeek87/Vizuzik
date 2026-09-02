package com.vizuzik.app.domain.library

import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.Artist
import com.vizuzik.app.domain.model.Track

/**
 * Dérive les albums et artistes à partir de la liste de morceaux plutôt que
 * de les stocker séparément : garantit que les identifiants restent toujours
 * cohérents avec ceux portés par [Track], quelle que soit la source.
 */
object LibraryAggregator {

    fun aggregateAlbums(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumId }
            .filterKeys { it.isNotBlank() }
            .map { (albumId, group) ->
                val first = group.first()
                Album(
                    id = albumId,
                    sourceType = first.sourceType,
                    title = first.album,
                    artist = first.albumArtist.ifBlank { first.artist },
                    artistId = first.artistId,
                    artworkUri = group.firstNotNullOfOrNull { it.artworkUri },
                    year = group.map { it.year }.filter { it > 0 }.maxOrNull() ?: 0,
                    trackCount = group.size,
                )
            }
            .sortedBy { it.title.lowercase() }

    fun aggregateArtists(tracks: List<Track>): List<Artist> =
        tracks.groupBy { it.artistId }
            .filterKeys { it.isNotBlank() }
            .map { (artistId, group) ->
                val first = group.first()
                Artist(
                    id = artistId,
                    sourceType = first.sourceType,
                    name = first.artist,
                    artworkUri = group.firstNotNullOfOrNull { it.artworkUri },
                    albumCount = group.map { it.albumId }.distinct().size,
                    trackCount = group.size,
                )
            }
            .sortedBy { it.name.lowercase() }
}
