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

    // Bottom navigation destinations.
    //
    // There is deliberately no `Player` here. The player is not a destination — it
    // is a state of the draggable surface over the graph — and modelling it as one
    // is what allowed `navigate(Screen.Player.route)` to survive its removal and
    // crash the app when a track was tapped. Without the object, that line cannot
    // be written. See [BottomNavItem].
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

    data object UnconfirmedMusic : Screen("unconfirmed-music")
    data object Favourites : Screen("favourites")
    data object Playlists : Screen("playlists")
    data object PlaylistDetail : Screen("playlists/{playlistId}") {
        fun createRoute(playlistId: Long): String = "playlists/$playlistId"
    }

}

/**
 * The three bottom tabs.
 *
 * [screen] is null for [Player] because the player has no route to navigate to: it
 * is the draggable surface layered over the graph, and its tab opens that rather than
 * going anywhere. Making the absence of a route part of the type is the point of this
 * enum — when the player was still a `Screen`, a stale `navigate(Screen.Player.route)`
 * compiled happily and crashed the app the moment a track was tapped. Now a tab
 * without a route cannot be navigated to, because there is nothing to pass.
 *
 * An enum rather than a lazy list of objects, which also retires a class-init hazard:
 * the old `bottomNavItems` had to be `by lazy`, because an eager `listOf(Player, …)`
 * inside `Screen`'s companion could observe a subclass object mid-construction and
 * silently produce a list containing null. Enum entries are initialised before any
 * code can reach `entries`, so the problem cannot arise.
 */
enum class BottomNavItem(val label: String, val screen: Screen?) {
    Player(label = "Player", screen = null),
    Library(label = "Library", screen = Screen.Library),
    Settings(label = "Settings", screen = Screen.Settings);

    /** Whether this tab is a navigation destination at all. */
    val isNavigable: Boolean get() = screen != null
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
