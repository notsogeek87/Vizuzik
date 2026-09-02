package com.vizuzik.app.domain.model

import android.net.Uri

/**
 * [id] est préfixé par la source (voir [MusicSourceType.idPrefix]), ex.
 * "local:1234" pour un morceau local. Une future [MusicSourceType.DEEZER]
 * produira ses propres identifiants ("deezer:5678") sans jamais collisionner.
 */
data class Track(
    val id: String,
    val sourceType: MusicSourceType,
    val title: String,
    val artist: String,
    val artistId: String,
    val album: String,
    val albumId: String,
    val albumArtist: String,
    val duration: Long,
    val uri: Uri,
    val artworkUri: Uri?,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val genre: String?,
    val dateAdded: Long = 0L,
)
