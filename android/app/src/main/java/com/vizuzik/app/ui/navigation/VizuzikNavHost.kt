package com.vizuzik.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.vizuzik.app.ui.albums.AlbumDetailScreen
import com.vizuzik.app.ui.albums.AlbumsScreen
import com.vizuzik.app.ui.artists.ArtistDetailScreen
import com.vizuzik.app.ui.artists.ArtistsScreen
import com.vizuzik.app.ui.diagnostics.MediaSessionProbeScreen
import com.vizuzik.app.ui.home.HomeScreen
import com.vizuzik.app.ui.player.EqualizerScreen
import com.vizuzik.app.ui.player.PlayerScreen
import com.vizuzik.app.ui.player.QueueScreen
import com.vizuzik.app.ui.player.VisualizerScreen
import com.vizuzik.app.ui.playlists.PlaylistDetailScreen
import com.vizuzik.app.ui.playlists.PlaylistsScreen
import com.vizuzik.app.ui.search.SearchScreen
import com.vizuzik.app.ui.settings.SettingsScreen
import com.vizuzik.app.ui.tracks.TracksScreen

@Composable
fun VizuzikNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = Destination.Home.route, modifier = modifier) {
        composable(Destination.Home.route) {
            HomeScreen(
                onOpenSearch = { navController.navigate(Destination.Search.route) },
                onOpenSettings = { navController.navigate(Destination.Settings.route) },
                onOpenAlbum = { id -> navController.navigate(Destination.AlbumDetail.createRoute(id)) },
            )
        }
        composable(Destination.Tracks.route) { TracksScreen() }
        composable(Destination.Albums.route) {
            AlbumsScreen(onOpenAlbum = { id -> navController.navigate(Destination.AlbumDetail.createRoute(id)) })
        }
        composable(Destination.Artists.route) {
            ArtistsScreen(onOpenArtist = { id -> navController.navigate(Destination.ArtistDetail.createRoute(id)) })
        }
        composable(Destination.Playlists.route) {
            PlaylistsScreen(onOpenPlaylist = { id -> navController.navigate(Destination.PlaylistDetail.createRoute(id)) })
        }
        composable(Destination.Search.route) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenAlbum = { id -> navController.navigate(Destination.AlbumDetail.createRoute(id)) },
                onOpenArtist = { id -> navController.navigate(Destination.ArtistDetail.createRoute(id)) },
            )
        }
        composable(Destination.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDeezerProbe = { navController.navigate(Destination.DeezerProbe.route) },
            )
        }
        composable(Destination.DeezerProbe.route) {
            MediaSessionProbeScreen(onBack = { navController.popBackStack() })
        }
        composable(Destination.Player.route) {
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onOpenQueue = { navController.navigate(Destination.Queue.route) },
                onOpenEqualizer = { navController.navigate(Destination.Equalizer.route) },
                onOpenVisualizer = { navController.navigate(Destination.Visualizer.route) },
            )
        }
        composable(Destination.Queue.route) { QueueScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.Equalizer.route) { EqualizerScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.Visualizer.route) { VisualizerScreen(onBack = { navController.popBackStack() }) }
        composable(
            Destination.AlbumDetail.route,
            arguments = listOf(navArgument(Destination.AlbumDetail.ARG) { type = NavType.StringType }),
        ) {
            AlbumDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(
            Destination.ArtistDetail.route,
            arguments = listOf(navArgument(Destination.ArtistDetail.ARG) { type = NavType.StringType }),
        ) {
            ArtistDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenAlbum = { id -> navController.navigate(Destination.AlbumDetail.createRoute(id)) },
            )
        }
        composable(
            Destination.PlaylistDetail.route,
            arguments = listOf(navArgument(Destination.PlaylistDetail.ARG) { type = NavType.LongType }),
        ) {
            PlaylistDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
