package com.vizuzik.app.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vizuzik.app.domain.model.MusicSourceType
import com.vizuzik.app.domain.model.Track

/**
 * Cache local des métadonnées scannées, pour éviter de rescanner
 * [android.provider.MediaStore] à chaque ouverture de l'application.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
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
    val dateAdded: Long,
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    sourceType = sourceType.name,
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    albumArtist = albumArtist,
    duration = duration,
    uri = uri,
    artworkUri = artworkUri,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    genre = genre,
    dateAdded = dateAdded,
)

fun TrackEntity.toDomain(): Track = Track(
    id = id,
    sourceType = MusicSourceType.valueOf(sourceType),
    title = title,
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    albumArtist = albumArtist,
    duration = duration,
    uri = uri,
    artworkUri = artworkUri,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    genre = genre,
    dateAdded = dateAdded,
)
