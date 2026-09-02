package com.vizuzik.app.testutil

import android.net.Uri
import com.vizuzik.app.domain.model.MusicSourceType
import com.vizuzik.app.domain.model.Track

/**
 * Ces tests JVM tournent sans Robolectric : [Uri.EMPTY] est le seul usage
 * d'[android.net.Uri] toléré (aucun test n'appelle de méthode dessus, seul
 * un remplissage de champ non-nul est nécessaire).
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
    uri = Uri.EMPTY,
    artworkUri = null,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    genre = null,
    dateAdded = dateAdded,
)
