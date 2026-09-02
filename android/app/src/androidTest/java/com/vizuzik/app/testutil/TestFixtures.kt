package com.vizuzik.app.testutil

import android.net.Uri
import com.vizuzik.app.domain.model.MusicSourceType
import com.vizuzik.app.domain.model.Track

/**
 * Dupliqué depuis `src/test` : les source sets `test` et `androidTest` sont
 * compilés séparément par AGP et ne peuvent pas partager de code sans
 * configuration `sharedTest` dédiée — non justifiée pour un simple fixture.
 */
fun testTrack(
    id: String,
    title: String = "Title $id",
    artist: String = "Artist A",
    album: String = "Album A",
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
    trackNumber = 1,
    discNumber = 1,
    year = 2020,
    genre = null,
    dateAdded = 0L,
)
