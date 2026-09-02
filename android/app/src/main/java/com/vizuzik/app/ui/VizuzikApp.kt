package com.vizuzik.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vizuzik.app.R
import com.vizuzik.app.ui.components.MiniPlayer
import com.vizuzik.app.ui.navigation.Destination
import com.vizuzik.app.ui.navigation.VizuzikNavHost
import com.vizuzik.app.ui.navigation.bottomBarDestinations
import com.vizuzik.app.ui.permissions.LibraryPermissionGate
import com.vizuzik.app.ui.player.PlayerViewModel

@Composable
fun VizuzikApp() {
    LibraryPermissionGate {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        val playerViewModel: PlayerViewModel = hiltViewModel()
        val playerState by playerViewModel.state.collectAsStateWithLifecycle()

        val showBottomBar = bottomBarDestinations.any { it.route == currentRoute }
        val showMiniPlayer = playerState.currentTrack != null && currentRoute != Destination.Player.route

        Scaffold(
            bottomBar = {
                Column {
                    if (showMiniPlayer) {
                        MiniPlayer(
                            state = playerState,
                            onClick = { navController.navigate(Destination.Player.route) },
                            onPlayPause = playerViewModel::togglePlayPause,
                            onNext = playerViewModel::skipToNext,
                        )
                    }
                    if (showBottomBar) {
                        NavigationBar {
                            bottomBarDestinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentRoute == destination.route,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(iconFor(destination), contentDescription = null) },
                                    label = { Text(labelFor(destination)) },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            VizuzikNavHost(navController = navController, modifier = Modifier.padding(padding))
        }
    }
}

private fun iconFor(destination: Destination): ImageVector = when (destination) {
    Destination.Home -> Icons.Filled.Home
    Destination.Tracks -> Icons.Filled.MusicNote
    Destination.Albums -> Icons.Filled.Album
    Destination.Artists -> Icons.Filled.Person
    Destination.Playlists -> Icons.Filled.QueueMusic
    else -> Icons.Filled.Home
}

@Composable
private fun labelFor(destination: Destination): String = when (destination) {
    Destination.Home -> stringResource(R.string.nav_home)
    Destination.Tracks -> stringResource(R.string.nav_tracks)
    Destination.Albums -> stringResource(R.string.nav_albums)
    Destination.Artists -> stringResource(R.string.nav_artists)
    Destination.Playlists -> stringResource(R.string.nav_playlists)
    else -> ""
}
