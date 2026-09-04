package com.bitperfect.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.ui.components.MiniPlayerBar
import com.bitperfect.android.ui.detail.TrackCollection
import com.bitperfect.android.ui.detail.TrackCollectionScreen
import com.bitperfect.android.ui.detail.TrackCollectionViewModel
import com.bitperfect.android.ui.diagnostics.DacDiagnosticsScreen
import com.bitperfect.android.ui.diagnostics.DiagnosticsViewModel
import com.bitperfect.android.ui.equalizer.EqualizerScreen
import com.bitperfect.android.ui.equalizer.EqualizerViewModel
import com.bitperfect.android.ui.library.LibraryScreen
import com.bitperfect.android.ui.library.LibraryViewModel
import com.bitperfect.android.ui.player.PlayerScreen
import com.bitperfect.android.ui.player.PlayerViewModel
import com.bitperfect.android.ui.queue.QueueScreen
import com.bitperfect.android.ui.queue.QueueViewModel
import com.bitperfect.android.ui.playlist.AddToPlaylistDialog
import com.bitperfect.android.ui.playlist.PlaylistsScreen
import com.bitperfect.android.ui.playlist.PlaylistsViewModel
import com.bitperfect.android.ui.settings.LicensesScreen
import com.bitperfect.android.ui.settings.UnconfirmedMusicScreen
import com.bitperfect.android.ui.settings.UnconfirmedMusicViewModel
import com.bitperfect.android.ui.settings.SettingsScreen
import com.bitperfect.android.ui.settings.SettingsViewModel
import com.bitperfect.android.ui.theme.BitPerfectMotion
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.bitperfect.android.ui.components.MINI_PLAYER_HEIGHT
import com.bitperfect.android.ui.player.PlayerSheet
import com.bitperfect.android.ui.player.rememberPlayerSheetState
import kotlinx.coroutines.launch

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
    equalizerViewModel: EqualizerViewModel,
    musicLibrary: MusicLibrary,
    onOpenFile: () -> Unit,
    onPickZip: () -> Unit = {},
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Created here rather than inside a `composable {}` block. Inside one, the
    // ViewModel store owner is the NavBackStackEntry, so every destination gets
    // its own instance no matter what key is used, and an edit on the playlist
    // detail screen would leave a stale track count on the list underneath.
    // Here the owner is the activity, so all destinations share one instance.
    val playlistsViewModel: PlaylistsViewModel = viewModel(
        factory = PlaylistsViewModel.Factory(musicLibrary, playerViewModel.playbackController)
    )

    // Determine if bottom nav should be shown
    // Player is deliberately absent: it is no longer a destination, so it is never
    // the current route. The bar stays visible while the player is open because the
    // route underneath it is still one of these, which is what it did before.
    val showBottomBar = currentDestination?.route in listOf(
        Screen.Library.route,
        Screen.Settings.route
    )

    val playerUiState by playerViewModel.uiState.collectAsState()

    // The player is one draggable surface over the navigation host rather than a
    // destination inside it. A NavHost composes one destination at a time, so while
    // the player was a sibling of the library the two were never on screen together
    // and no continuous gesture between them was possible. See PlayerSheet.
    val playerSheetState = rememberPlayerSheetState(initiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()

    // The collapsed bar shows only when there is something to collapse to, and never
    // over the queue, which is a full-screen list of the same thing. Zero peek keeps
    // the player composed — so it holds its state and its effects keep running —
    // while leaving it off screen.
    val hasTrack = playerUiState.isPlaying || playerUiState.isPaused
    val peekHeight = if (hasTrack && currentDestination?.route != Screen.Queue.route) {
        MINI_PLAYER_HEIGHT
    } else {
        0.dp
    }

    /** Collapse before navigating, or the destination opens underneath the player. */
    fun navigatingAwayFromPlayer(navigate: () -> Unit) {
        sheetScope.launch { playerSheetState.collapse() }
        navigate()
    }

    // Back collapses the player rather than leaving the app, and only while it is
    // open, so every other screen's back behaviour is untouched.
    BackHandler(enabled = playerSheetState.isExpanded) {
        sheetScope.launch { playerSheetState.collapse() }
    }

    // Track the player's overflow menu is adding to a playlist, if any.
    var playerPlaylistTrack by remember { mutableStateOf<String?>(null) }

    // Opening the player is now expanding the surface, not navigating.
    val openPlayer: () -> Unit = { sheetScope.launch { playerSheetState.expand() } }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BitPerfectBottomNav(
                    currentRoute = currentDestination?.route,
                    isPlayerExpanded = playerSheetState.isExpanded,
                    onItemSelected = { item ->
                        val target = item.screen
                        if (target == null) {
                            openPlayer()
                        } else {
                            // Collapses first. Without this the navigation happened
                            // underneath the open player, which stayed on top — so
                            // tapping Library or Settings from the player looked like
                            // it did nothing at all.
                            navigatingAwayFromPlayer {
                                navController.navigate(target.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
        NavHost(
            navController = navController,
            // Reserves the strip the collapsed bar sits in. The bar used to be in the
            // Scaffold's bottomBar, which inset every screen for free; as an overlay
            // it no longer does, and without this it covers the bottom of whatever is
            // behind it — the last row of a list, or a control on the equalizer.
            modifier = Modifier.padding(bottom = peekHeight),
            startDestination = Screen.Library.route,
            // Forward navigation slides in from the trailing edge and back
            // reverses it, so the hierarchy reads as depth rather than a cut.
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(
                        BitPerfectMotion.DURATION_SCREEN,
                        easing = BitPerfectMotion.EmphasisedDecelerate
                    ),
                    initialOffsetX = { fullWidth -> fullWidth / 5 }
                ) + fadeIn(tween(BitPerfectMotion.DURATION_STANDARD))
            },
            exitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(
                        BitPerfectMotion.DURATION_SCREEN,
                        easing = BitPerfectMotion.EmphasisedAccelerate
                    ),
                    targetOffsetX = { fullWidth -> -fullWidth / 6 }
                ) + fadeOut(tween(BitPerfectMotion.DURATION_QUICK))
            },
            popEnterTransition = {
                slideInHorizontally(
                    animationSpec = tween(
                        BitPerfectMotion.DURATION_SCREEN,
                        easing = BitPerfectMotion.EmphasisedDecelerate
                    ),
                    initialOffsetX = { fullWidth -> -fullWidth / 6 }
                ) + fadeIn(tween(BitPerfectMotion.DURATION_STANDARD))
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(
                        BitPerfectMotion.DURATION_SCREEN,
                        easing = BitPerfectMotion.EmphasisedAccelerate
                    ),
                    targetOffsetX = { fullWidth -> fullWidth / 5 }
                ) + fadeOut(tween(BitPerfectMotion.DURATION_QUICK))
            }
        ) {
            // Library screen
            composable(Screen.Library.route) {
                var pendingLibraryPlaylistTrack by remember { mutableStateOf<String?>(null) }

                LibraryScreen(
                    viewModel = libraryViewModel,
                    onAlbumClick = { albumId ->
                        navController.navigate(Screen.AlbumTracks.createRoute(albumId))
                    },
                    onArtistClick = { artistId ->
                        navController.navigate(Screen.ArtistAlbums.createRoute(artistId))
                    },
                    onGenreClick = { name ->
                        navController.navigate(Screen.GenreTracks.createRoute(name))
                    },
                    onComposerClick = { name ->
                        navController.navigate(Screen.ComposerTracks.createRoute(name))
                    },
                    onFolderClick = { path ->
                        navController.navigate(Screen.FolderTracks.createRoute(path))
                    },
                    onFavouritesClick = {
                        navController.navigate(Screen.Favourites.route)
                    },
                    onPlaylistsClick = {
                        navController.navigate(Screen.Playlists.route)
                    },
                    onPickZip = onPickZip,
                    onTrackClick = { visibleTracks, index ->
                        playerViewModel.playFromLibrary(visibleTracks, index)
                        // Expands the surface. This navigated to a "player" route
                        // until that destination was removed, at which point tapping
                        // a track crashed — see the note on BottomNavItem.
                        openPlayer()
                    },
                    onAddToPlaylist = { path -> pendingLibraryPlaylistTrack = path },
                    nowPlayingPath = playerUiState.trackPath
                )

                // Hosted here, not in the screen, because the playlist picker
                // needs the graph-scoped PlaylistsViewModel that every other
                // route shares.
                pendingLibraryPlaylistTrack?.let { path ->
                    AddToPlaylistDialog(
                        trackPath = path,
                        viewModel = playlistsViewModel,
                        onDismiss = { pendingLibraryPlaylistTrack = null },
                        onResult = libraryViewModel::showStatusMessage
                    )
                }
            }

            // Settings screen
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onDiagnosticsClick = { navController.navigate(Screen.Diagnostics.route) },
                    onLicensesClick = { navController.navigate(Screen.Licenses.route) },
                    onUnconfirmedMusicClick = {
                        navController.navigate(Screen.UnconfirmedMusic.route)
                    },
                    onRebuildArtwork = { settingsViewModel.rebuildArtwork() }
                )
            }

            // Diagnostics screen
            composable(Screen.Diagnostics.route) {
                DacDiagnosticsScreen(
                    viewModel = diagnosticsViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Equalizer (Android output path only)
            composable(Screen.Equalizer.route) {
                EqualizerScreen(
                    viewModel = equalizerViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Album detail
            composable(
                route = Screen.AlbumTracks.route,
                arguments = listOf(navArgument("albumId") { type = NavType.LongType })
            ) { backStackEntry ->
                val albumId = backStackEntry.arguments?.getLong("albumId") ?: 0L
                var pendingPlaylistTrack by remember { mutableStateOf<String?>(null) }
                val collectionViewModel = trackCollectionViewModel(
                    TrackCollection.OfAlbum(albumId),
                    musicLibrary,
                    playerViewModel.playbackController
                )

                pendingPlaylistTrack?.let { path ->
                    AddToPlaylistDialog(
                        trackPath = path,
                        viewModel = playlistsViewModel,
                        onDismiss = { pendingPlaylistTrack = null },
                        // Surface the result in this screen's snackbar; the
                        // playlist ViewModel's own state is not shown here.
                        onResult = collectionViewModel::showExternalMessage
                    )
                }

                TrackCollectionScreen(
                    viewModel = collectionViewModel,
                    onBackClick = { navController.popBackStack() },
                    onAddToPlaylist = { path -> pendingPlaylistTrack = path }
                )
            }

            // Artist detail: their albums and every track
            composable(
                route = Screen.ArtistAlbums.route,
                arguments = listOf(navArgument("artistId") { type = NavType.LongType })
            ) { backStackEntry ->
                val artistId = backStackEntry.arguments?.getLong("artistId") ?: 0L
                var pendingPlaylistTrack by remember { mutableStateOf<String?>(null) }
                val collectionViewModel = trackCollectionViewModel(
                    TrackCollection.OfArtist(artistId),
                    musicLibrary,
                    playerViewModel.playbackController
                )

                pendingPlaylistTrack?.let { path ->
                    AddToPlaylistDialog(
                        trackPath = path,
                        viewModel = playlistsViewModel,
                        onDismiss = { pendingPlaylistTrack = null },
                        // Surface the result in this screen's snackbar; the
                        // playlist ViewModel's own state is not shown here.
                        onResult = collectionViewModel::showExternalMessage
                    )
                }

                TrackCollectionScreen(
                    viewModel = collectionViewModel,
                    onBackClick = { navController.popBackStack() },
                    onAlbumClick = { albumId ->
                        navController.navigate(Screen.AlbumTracks.createRoute(albumId))
                    },
                    onAddToPlaylist = { path -> pendingPlaylistTrack = path }
                )
            }

            // Genre detail
            composable(
                route = Screen.GenreTracks.route,
                arguments = listOf(navArgument("genreName") { type = NavType.StringType })
            ) { backStackEntry ->
                val name = backStackEntry.arguments?.getString("genreName").orEmpty()
                TrackCollectionScreen(
                    viewModel = trackCollectionViewModel(
                        TrackCollection.OfGenre(decodeNavArg(name)),
                        musicLibrary,
                        playerViewModel.playbackController
                    ),
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Composer detail
            composable(
                route = Screen.ComposerTracks.route,
                arguments = listOf(navArgument("composerName") { type = NavType.StringType })
            ) { backStackEntry ->
                val name = backStackEntry.arguments?.getString("composerName").orEmpty()
                TrackCollectionScreen(
                    viewModel = trackCollectionViewModel(
                        TrackCollection.OfComposer(decodeNavArg(name)),
                        musicLibrary,
                        playerViewModel.playbackController
                    ),
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Folder detail
            composable(
                route = Screen.FolderTracks.route,
                arguments = listOf(navArgument("folderPath") { type = NavType.StringType })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("folderPath").orEmpty()
                TrackCollectionScreen(
                    viewModel = trackCollectionViewModel(
                        TrackCollection.OfFolder(decodeNavArg(path)),
                        musicLibrary,
                        playerViewModel.playbackController
                    ),
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Playlists
            composable(Screen.Playlists.route) {
                PlaylistsScreen(
                    viewModel = playlistsViewModel,
                    onBackClick = { navController.popBackStack() },
                    onPlaylistClick = { playlistId ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                    }
                )
            }

            // Playlist detail
            composable(
                route = Screen.PlaylistDetail.route,
                arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: 0L
                val collectionViewModel = trackCollectionViewModel(
                    TrackCollection.OfPlaylist(playlistId),
                    musicLibrary,
                    playerViewModel.playbackController
                )
                val playlists = playlistsViewModel

                TrackCollectionScreen(
                    viewModel = collectionViewModel,
                    onBackClick = { navController.popBackStack() },
                    onRemoveFromCollection = { trackId ->
                        playlists.removeTrackFromPlaylist(playlistId, trackId) {
                            collectionViewModel.refresh()
                        }
                    },
                    removeLabel = "Remove from playlist"
                )
            }

            // Favourites
            composable(Screen.Favourites.route) {
                val favourites = trackCollectionViewModel(
                    TrackCollection.Favourites,
                    musicLibrary,
                    playerViewModel.playbackController
                )
                TrackCollectionScreen(
                    viewModel = favourites,
                    onBackClick = { navController.popBackStack() },
                    // Un-favouriting is what "remove" means in this list, so
                    // the row action reuses the favourite toggle.
                    onRemoveFromCollection = favourites::toggleFavourite,
                    removeLabel = "Remove favourite"
                )
            }

            // Now Playing queue
            composable(Screen.Queue.route) {
                QueueScreen(
                    viewModel = viewModel(
                        key = "queue",
                        factory = QueueViewModel.Factory(
                            musicLibrary,
                            playerViewModel.playbackController
                        )
                    ),
                    onBackClick = { navController.popBackStack() }
                )
            }

            // Licenses screen
            composable(Screen.Licenses.route) {
                LicensesScreen(onBackClick = { navController.popBackStack() })
            }

            // Files the scanner judged probably-not-music, with a move-to-library
            // action. Scoped to this destination: the list is reloaded on entry,
            // so there is no state worth sharing with the rest of the graph.
            composable(Screen.UnconfirmedMusic.route) {
                val unconfirmedViewModel: UnconfirmedMusicViewModel = viewModel(
                    factory = UnconfirmedMusicViewModel.Factory(musicLibrary)
                )
                UnconfirmedMusicScreen(
                    viewModel = unconfirmedViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }

            // The player, over the navigation host rather than inside it. Both
            // faces are the existing composables, unchanged; only where they live
            // and how the surface moves are new.
            PlayerSheet(
                state = playerSheetState,
                peekHeight = peekHeight,
                miniPlayer = { drag ->
                    MiniPlayerBar(
                        uiState = playerUiState,
                        onBarClick = openPlayer,
                        onExpand = openPlayer,
                        onPlayPauseClick = { playerViewModel.togglePlayPause() },
                        onSwipeNext = { playerViewModel.nextOrWrap() },
                        onSwipePrevious = { playerViewModel.previous() },
                        sheetDrag = drag
                    )
                },
                fullPlayer = { drag ->
                    PlayerScreen(
                        viewModel = playerViewModel,
                        onOpenFile = { navigatingAwayFromPlayer(onOpenFile) },
                        // Collapsing is now a position on this surface rather than a
                        // navigation, so there is no back stack to pop and none of
                        // the old fallback is needed.
                        onCollapse = { sheetScope.launch { playerSheetState.collapse() } },
                        onAlbumArtClick = {
                            // Jump the library to the playing song, in the Tracks tab.
                            playerViewModel.currentTrackPath()?.let { path ->
                                libraryViewModel.openTrackInList(path)
                            }
                            navigatingAwayFromPlayer {
                                navController.navigate(Screen.Library.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        // Every one of these collapses first: the destination would
                        // otherwise open underneath the expanded player.
                        onEqualizerClick = {
                            navigatingAwayFromPlayer {
                                navController.navigate(Screen.Equalizer.route)
                            }
                        },
                        onQueueClick = {
                            navigatingAwayFromPlayer {
                                navController.navigate(Screen.Queue.route)
                            }
                        },
                        onAddToPlaylist = { path -> playerPlaylistTrack = path },
                        onGoToAlbum = { albumId ->
                            navigatingAwayFromPlayer {
                                navController.navigate(Screen.AlbumTracks.createRoute(albumId))
                            }
                        },
                        onGoToArtist = { artistId ->
                            navigatingAwayFromPlayer {
                                navController.navigate(Screen.ArtistAlbums.createRoute(artistId))
                            }
                        },
                        onGoToFolder = { path ->
                            navigatingAwayFromPlayer {
                                navController.navigate(Screen.FolderTracks.createRoute(path))
                            }
                        },
                        onGoToGenre = { name ->
                            navigatingAwayFromPlayer {
                                navController.navigate(Screen.GenreTracks.createRoute(name))
                            }
                        },
                        sheetDrag = drag
                    )
                }
            )

            // Hosted here rather than inside PlayerScreen so the dialog can use the
            // graph-scoped PlaylistsViewModel that every other screen shares.
            playerPlaylistTrack?.let { path ->
                AddToPlaylistDialog(
                    trackPath = path,
                    viewModel = playlistsViewModel,
                    onDismiss = { playerPlaylistTrack = null },
                    onResult = playerViewModel::showExternalMessage
                )
            }
        }
    }
}

@Composable
private fun BitPerfectBottomNav(
    currentRoute: String?,
    /** Whether the player surface is open, which is what "Player" now means. */
    isPlayerExpanded: Boolean,
    /**
     * What to do about a tab. Navigation is the caller's business rather than this
     * bar's, because selecting a tab also has to collapse the player — and a bar that
     * navigated for itself is how that came to be forgotten.
     */
    onItemSelected: (BottomNavItem) -> Unit
) {
    NavigationBar {
        BottomNavItem.entries.forEach { item ->
            // The player has no route, so its tab reflects the surface rather than
            // the back stack. The type says so — see BottomNavItem.
            val target = item.screen
            val isSelected = if (target == null) {
                isPlayerExpanded
            } else {
                currentRoute == target.route && !isPlayerExpanded
            }

            NavigationBarItem(
                selected = isSelected,
                onClick = { onItemSelected(item) },
                icon = {
                    val iconRes = when (item) {
                        BottomNavItem.Player -> R.drawable.ic_play
                        BottomNavItem.Library -> R.drawable.ic_library
                        BottomNavItem.Settings -> R.drawable.ic_settings
                    }
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = item.label
                    )
                },
                label = { Text(item.label) }
            )
        }
    }
}


/**
 * A [TrackCollectionViewModel] scoped to the current navigation entry.
 *
 * Scoping it to the back stack entry rather than the activity means each
 * album or artist screen keeps its own state, and it is cleared when that entry
 * leaves the back stack - which is what removes its playback state listener.
 */
@Composable
private fun trackCollectionViewModel(
    collection: TrackCollection,
    musicLibrary: MusicLibrary,
    playbackController: PlaybackController
): TrackCollectionViewModel = viewModel(
    key = collection.toString(),
    factory = TrackCollectionViewModel.Factory(
        collection = collection,
        musicLibrary = musicLibrary,
        playbackController = playbackController
    )
)


