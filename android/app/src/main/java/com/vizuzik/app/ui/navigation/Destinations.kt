package com.vizuzik.app.ui.navigation

import android.net.Uri

sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object Tracks : Destination("tracks")
    data object Albums : Destination("albums")
    data object Artists : Destination("artists")
    data object Playlists : Destination("playlists")
    data object Search : Destination("search")
    data object Settings : Destination("settings")
    data object Player : Destination("player")
    data object Queue : Destination("queue")
    data object Equalizer : Destination("equalizer")
    data object Visualizer : Destination("visualizer")
    data object DeezerProbe : Destination("deezer-probe")

    data object AlbumDetail : Destination("album/{albumId}") {
        const val ARG = "albumId"
        fun createRoute(albumId: String) = "album/${Uri.encode(albumId)}"
    }

    data object ArtistDetail : Destination("artist/{artistId}") {
        const val ARG = "artistId"
        fun createRoute(artistId: String) = "artist/${Uri.encode(artistId)}"
    }

    data object PlaylistDetail : Destination("playlist/{playlistId}") {
        const val ARG = "playlistId"
        fun createRoute(playlistId: Long) = "playlist/$playlistId"
    }
}

val bottomBarDestinations = listOf(
    Destination.Home,
    Destination.Tracks,
    Destination.Albums,
    Destination.Artists,
    Destination.Playlists,
)
