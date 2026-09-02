package com.vizuzik.app.testutil

import com.vizuzik.app.domain.model.MusicSourceType
import com.vizuzik.app.domain.model.Track

/**
 * Aucune dépendance au framework Android : le modèle de domaine porte des
 * URIs sous forme de chaînes, donc ces tests tournent en JVM pur, sans
 * Robolectric ni android.jar mocké.
 */
fun testTrack(
    id: String,
    title: String = "Title $id",
    artist: String = "Artist A",
    album: String = "Album A",
    dateAdded: Long = 0L,
    year: Int = 2020,
    trackNumber: Int = 1,
    discNumber: Int = 1,
): Track = Track(
    id = id,
    sourceType = MusicSourceType.LOCAL,
    title = title,
    artist = artist,
    artistId = "local:artist:${artist.hashCode()}",
    album = album,
    albumId = "local:album:${album.hashCode()}",
    albumArtist = artist,
    duration = 200_000L,
    uri = "content://media/external/audio/media/$id",
    artworkUri = null,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    genre = null,
    dateAdded = dateAdded,
)
