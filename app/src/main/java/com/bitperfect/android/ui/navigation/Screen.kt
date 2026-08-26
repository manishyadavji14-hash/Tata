package com.bitperfect.android.ui.navigation

/**
 * Screen - Sealed class defining all navigation routes in BitPerfect.
 *
 * Navigation hierarchy:
 * - Bottom nav: Player, Library, Settings
 * - Library nested: Albums -> Tracks, Artists -> Albums -> Tracks
 * - Diagnostics: Accessible from Settings or Player
 */
sealed class Screen(val route: String) {

    // Bottom navigation destinations
    data object Player : Screen("player")
    data object Library : Screen("library")
    data object Settings : Screen("settings")

    // Library nested screens
    data object AlbumTracks : Screen("library/album/{albumId}") {
        fun createRoute(albumId: Long): String = "library/album/$albumId"
    }
    data object ArtistAlbums : Screen("library/artist/{artistId}") {
        fun createRoute(artistId: Long): String = "library/artist/$artistId"
    }
    data object GenreTracks : Screen("library/genre/{genreId}") {
        fun createRoute(genreId: Long): String = "library/genre/$genreId"
    }
    data object ComposerTracks : Screen("library/composer/{composerId}") {
        fun createRoute(composerId: Long): String = "library/composer/$composerId"
    }

    // Standalone screens
    data object Diagnostics : Screen("diagnostics")
    data object Queue : Screen("queue")
    data object Licenses : Screen("licenses")

    companion object {
        /**
         * All bottom navigation items.
         */
        val bottomNavItems = listOf(Player, Library, Settings)

        /**
         * Get the display label for a bottom nav item.
         */
        fun getLabel(screen: Screen): String = when (screen) {
            Player -> "Player"
            Library -> "Library"
            Settings -> "Settings"
            else -> ""
        }

        /**
         * Get the icon resource name for a bottom nav item.
         */
        fun getIconRes(screen: Screen): String = when (screen) {
            Player -> "ic_play"
            Library -> "ic_library"
            Settings -> "ic_settings"
            else -> ""
        }
    }
}
