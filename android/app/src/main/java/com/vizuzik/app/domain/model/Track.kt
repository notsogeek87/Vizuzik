package com.vizuzik.app.domain.model

/**
 * [id] est préfixé par la source (voir [MusicSourceType.idPrefix]), ex.
 * "local:1234" pour un morceau local. Une future [MusicSourceType.DEEZER]
 * produira ses propres identifiants ("deezer:5678") sans jamais collisionner.
 *
 * [uri] et [artworkUri] sont des chaînes et non des [android.net.Uri] : le
 * domaine reste indépendant du framework Android (donc testable en JVM pur),
 * et une source distante y mettra naturellement ses URLs HTTPS.
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
    val uri: String,
    val artworkUri: String?,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int,
    val genre: String?,
    val dateAdded: Long = 0L,
)
