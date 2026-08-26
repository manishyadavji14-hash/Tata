package com.bitperfect.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bitperfect.android.R
import com.bitperfect.android.ui.diagnostics.DacDiagnosticsScreen
import com.bitperfect.android.ui.diagnostics.DiagnosticsViewModel
import com.bitperfect.android.ui.library.LibraryScreen
import com.bitperfect.android.ui.library.LibraryViewModel
import com.bitperfect.android.ui.player.PlayerScreen
import com.bitperfect.android.ui.player.PlayerViewModel
import com.bitperfect.android.ui.queue.QueueScreen
import com.bitperfect.android.ui.queue.QueueViewModel
import com.bitperfect.android.ui.settings.SettingsScreen
import com.bitperfect.android.ui.settings.SettingsViewModel

/**
 * NavGraph - Main navigation setup for BitPerfect.
 *
 * Structure:
 * - Bottom navigation with Player, Library, Settings
 * - Nested navigation for library (albums -> tracks, artists -> albums)
 * - Diagnostics accessible from Settings or Player
 * - Full-screen destinations (Queue, Diagnostics) overlay bottom nav
 */
@Composable
fun BitPerfectNavGraph(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    settingsViewModel: SettingsViewModel,
    diagnosticsViewModel: DiagnosticsViewModel,
    queueViewModel: QueueViewModel,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Determine if bottom nav should be shown
    val showBottomBar = currentDestination?.route in listOf(
        Screen.Player.route,
        Screen.Library.route,
        Screen.Settings.route
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BitPerfectBottomNav(
                    navController = navController,
                    currentRoute = currentDestination?.route
                )
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Player.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            // Player screen
            composable(Screen.Player.route) {
                PlayerScreen(
                    viewModel = playerViewModel,
                    onQueueClick = { navController.navigate(Screen.Queue.route) },
                    onDiagnosticsClick = { navController.navigate(Screen.Diagnostics.route) }
                )
            }

            // Library screen
            composable(Screen.Library.route) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onAlbumClick = { albumId ->
                        navController.navigate(Screen.AlbumTracks.createRoute(albumId))
                    },
                    onArtistClick = { artistId ->
                        navController.navigate(Screen.ArtistAlbums.createRoute(artistId))
                    },
                    onTrackClick = { trackPath ->
                        // Get the current visible track list from the library
                        val currentTracks = libraryViewModel.uiState.value.tracks
                        val trackPaths = currentTracks.map { it.path }
                        val selectedIndex = trackPaths.indexOf(trackPath).coerceAtLeast(0)

                        // Set the queue and start playback
                        playerViewModel.playFromLibrary(trackPaths, selectedIndex)

                        // Navigate to the player screen
                        navController.navigate(Screen.Player.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            // Settings screen
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onDiagnosticsClick = { navController.navigate(Screen.Diagnostics.route) },
                    onLicensesClick = { navController.navigate(Screen.Licenses.route) }
                )
            }

            // Diagnostics screen
            composable(Screen.Diagnostics.route) {
                DacDiagnosticsScreen(
                    viewModel = diagnosticsViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Album tracks (nested library navigation)
            composable(
                route = Screen.AlbumTracks.route,
                arguments = listOf(navArgument("albumId") { type = NavType.LongType })
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getLong("albumId") ?: 0L
                // Album detail screen - shows library filtered by album
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onTrackClick = { trackPath ->
                        // Get the current visible track list from the library
                        val currentTracks = libraryViewModel.uiState.value.tracks
                        val trackPaths = currentTracks.map { it.path }
                        val selectedIndex = trackPaths.indexOf(trackPath).coerceAtLeast(0)

                        // Set the queue and start playback
                        playerViewModel.playFromLibrary(trackPaths, selectedIndex)

                        // Navigate to the player screen
                        navController.navigate(Screen.Player.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    }
                )
            }

            // Artist albums (nested library navigation)
            composable(
                route = Screen.ArtistAlbums.route,
                arguments = listOf(navArgument("artistId") { type = NavType.LongType })
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getLong("artistId") ?: 0L
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onAlbumClick = { albumId ->
                        navController.navigate(Screen.AlbumTracks.createRoute(albumId))
                    }
                )
            }

            // Queue screen
            composable(Screen.Queue.route) {
                QueueScreen(
                    viewModel = queueViewModel,
                    onBackClick = { navController.popBackStack() },
                    onTrackClick = { index ->
                        queueViewModel.jumpToTrack(index)
                        navController.popBackStack()
                    }
                )
            }

            // Licenses screen
            composable(Screen.Licenses.route) {
                // Licenses placeholder
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onDiagnosticsClick = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun BitPerfectBottomNav(
    navController: NavHostController,
    currentRoute: String?
) {
    NavigationBar {
        Screen.bottomNavItems.forEach { screen ->
            val isSelected = currentRoute == screen.route

            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    val iconRes = when (screen) {
                        Screen.Player -> R.drawable.ic_play
                        Screen.Library -> R.drawable.ic_library
                        Screen.Settings -> R.drawable.ic_settings
                        else -> R.drawable.ic_play
                    }
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = Screen.getLabel(screen)
                    )
                },
                label = { Text(Screen.getLabel(screen)) }
            )
        }
    }
}
