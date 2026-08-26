package com.bitperfect.android.ui.navigation

import java.util.Base64

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
    /**
     * Genres, composers and folders are addressed by free text rather than an
     * id, so their routes go through [encodeNavArg]. See that function for why
     * plain URL-encoding is not enough.
     */
    data object GenreTracks : Screen("library/genre/{genreName}") {
        fun createRoute(name: String): String = "library/genre/${encodeNavArg(name)}"
    }
    data object ComposerTracks : Screen("library/composer/{composerName}") {
        fun createRoute(name: String): String = "library/composer/${encodeNavArg(name)}"
    }
    data object FolderTracks : Screen("library/folder/{folderPath}") {
        fun createRoute(path: String): String = "library/folder/${encodeNavArg(path)}"
    }

    // Standalone screens
    data object Diagnostics : Screen("diagnostics")
    data object Equalizer : Screen("equalizer")
    data object Queue : Screen("queue")
    data object Licenses : Screen("licenses")
    data object Favourites : Screen("favourites")
    data object Playlists : Screen("playlists")
    data object PlaylistDetail : Screen("playlists/{playlistId}") {
        fun createRoute(playlistId: Long): String = "playlists/$playlistId"
    }

    companion object {
        /**
         * All bottom navigation items.
         *
         * MUST stay lazy. An eager initializer here deadlocks on class-init
         * ordering: touching any subclass (e.g. `Screen.Player.route`) starts
         * `Screen$Player.<clinit>`, whose constructor triggers `Screen.<clinit>`,
         * which would then read `Screen$Player.INSTANCE` while that field is
         * still unassigned. The JVM returns immediately from the re-entrant
         * init rather than re-running it, so the reference comes back null and
         * the list is permanently poisoned with a null element -- which type
         * erasure lets through unnoticed until something dereferences it.
         *
         * Deferring evaluation guarantees every object is fully constructed
         * before the list is built.
         */
        val bottomNavItems: List<Screen> by lazy {
            listOf(Player, Library, Settings)
        }

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


/**
 * Marks an encoded navigation argument so the path segment is never empty.
 *
 * `~` is safe in a URL path and is not part of the Base64 URL-safe alphabet,
 * so it can always be told apart from the payload that follows it.
 */
private const val NAV_ARG_MARKER = "~"

/**
 * Encodes a free-text value for use as a single path segment.
 *
 * Plain `Uri.encode` is not sufficient here for two reasons:
 *
 * 1. `Uri.encode("")` returns `""`, which produces a route like `library/genre/`
 *    with an empty segment. That matches no destination and `navigate` throws.
 *    A blank genre is the normal case below API 30, where the genre column does
 *    not exist, so this is a routine input rather than an edge case.
 * 2. Percent-encoded values are fragile to decode: whether Navigation has
 *    already decoded a captured argument determines if a second decode is
 *    correct or corrupting, and a value containing a literal `%` is silently
 *    mangled if it is decoded twice.
 *
 * Base64 URL-safe output contains only `A-Z a-z 0-9 - _`, so it survives any
 * number of percent-decodes unchanged, and the leading marker guarantees a
 * non-empty segment. Padding is dropped because `=` is awkward in a path.
 */
internal fun encodeNavArg(value: String): String =
    NAV_ARG_MARKER + Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

/**
 * Reverses [encodeNavArg].
 *
 * Returns an empty string for a malformed argument rather than throwing, so a
 * hand-typed or stale deep link lands on an empty collection instead of
 * crashing.
 */
internal fun decodeNavArg(encoded: String): String {
    val payload = encoded.removePrefix(NAV_ARG_MARKER)
    if (payload.isEmpty()) return ""
    return try {
        String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        ""
    }
}
