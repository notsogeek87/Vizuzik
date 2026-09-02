package com.vizuzik.app.data.local.mediastore

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.vizuzik.app.R
import com.vizuzik.app.di.IoDispatcher
import com.vizuzik.app.domain.model.MusicSourceType
import com.vizuzik.app.domain.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seul point du projet qui interroge [MediaStore]. Toute autre couche
 * manipule uniquement des [Track] du domaine.
 */
@Singleton
class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun queryTracks(): List<Track> = withContext(ioDispatcher) {
        val tracks = mutableListOf<Track>()
        val unknownArtist = context.getString(R.string.unknown_artist)
        val unknownAlbum = context.getString(R.string.unknown_album)

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)
        }.getOrNull()?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val artistId = cursor.getLong(artistIdCol)
                val rawTrackNumber = if (trackCol >= 0) cursor.getInt(trackCol) else 0
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: continue
                val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: unknownArtist
                val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: unknownAlbum
                val albumArtist = albumArtistCol.takeIf { it >= 0 }?.let { cursor.getString(it) }?.takeIf { it.isNotBlank() } ?: artist

                tracks += Track(
                    id = "${MusicSourceType.LOCAL.idPrefix}:$id",
                    sourceType = MusicSourceType.LOCAL,
                    title = title,
                    artist = artist,
                    artistId = "${MusicSourceType.LOCAL.idPrefix}:artist:$artistId",
                    album = album,
                    albumId = "${MusicSourceType.LOCAL.idPrefix}:album:$albumId",
                    albumArtist = albumArtist,
                    duration = cursor.getLong(durationCol),
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    artworkUri = albumArtUri(albumId),
                    trackNumber = if (rawTrackNumber >= 1000) rawTrackNumber % 1000 else rawTrackNumber,
                    discNumber = if (rawTrackNumber >= 1000) rawTrackNumber / 1000 else 1,
                    year = if (yearCol >= 0) cursor.getInt(yearCol) else 0,
                    genre = null,
                    dateAdded = (if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L) * 1000L,
                )
            }
        }
        tracks
    }

    private fun albumArtUri(albumId: Long): String =
        "content://media/external/audio/albumart/$albumId"
}
