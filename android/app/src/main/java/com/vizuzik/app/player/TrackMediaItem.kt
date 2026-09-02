package com.vizuzik.app.player

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.vizuzik.app.domain.model.RepeatMode
import com.vizuzik.app.domain.model.Track
import androidx.media3.common.Player as Media3Player

/**
 * Les métadonnées embarquées ici pilotent automatiquement la notification
 * média, l'écran de verrouillage et l'affichage Bluetooth (AVRCP) via
 * [androidx.media3.session.DefaultMediaNotificationProvider] — aucun code
 * de notification manuel n'est nécessaire.
 */
fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setArtworkUri(artworkUri?.toUri())
                .setTrackNumber(trackNumber.takeIf { it > 0 })
                .setDiscNumber(discNumber.takeIf { it > 0 })
                .setRecordingYear(year.takeIf { it > 0 })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()

fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> Media3Player.REPEAT_MODE_OFF
    RepeatMode.ONE -> Media3Player.REPEAT_MODE_ONE
    RepeatMode.ALL -> Media3Player.REPEAT_MODE_ALL
}

fun Int.toDomainRepeatMode(): RepeatMode = when (this) {
    Media3Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Media3Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}
