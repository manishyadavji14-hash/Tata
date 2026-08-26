package com.bitperfect.android.ui.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the free-text navigation argument codec.
 *
 * These guard the two failure modes that made genre and composer routes crash:
 * an empty encoded segment, and a value being percent-decoded twice.
 */
@DisplayName("Nav argument codec")
class NavArgCodecTest {

    @Test
    @DisplayName("Blank name still produces a non-empty segment")
    fun blankIsRoutable() {
        val encoded = encodeNavArg("")
        assertTrue(encoded.isNotEmpty(), "an empty segment matches no destination")
        assertEquals("", decodeNavArg(encoded))
    }

    @Test
    @DisplayName("Blank genre route has a segment after the prefix")
    fun blankGenreRouteIsMatchable() {
        val route = Screen.GenreTracks.createRoute("")
        assertFalse(route.endsWith("/"), "route must not end with an empty segment")
        assertEquals("library/genre/~", route)
    }

    @Test
    @DisplayName("Encoded output contains no percent or slash")
    fun encodedIsPathSafe() {
        val awkward = "Hip-Hop/Rap 50% & Soul_Funk"
        val encoded = encodeNavArg(awkward)
        assertFalse(encoded.contains('%'), "percent would be re-decoded")
        assertFalse(encoded.contains('/'), "slash would split the path segment")
        assertEquals(awkward, decodeNavArg(encoded))
    }

    @Test
    @DisplayName("Decoding twice is harmless")
    fun decodeIsIdempotentAgainstDoubleDecode() {
        // Navigation may already decode a captured argument. Because the
        // encoding emits no percent sequences, an extra pass changes nothing.
        val value = "Composer 100% Live"
        val encoded = encodeNavArg(value)
        assertEquals(value, decodeNavArg(encoded))
        assertEquals(encoded, encoded.replace("%25", "%"))
    }

    @Test
    @DisplayName("Round trips slashes, unicode and spaces")
    fun roundTripsAwkwardValues() {
        val values = listOf(
            "/storage/emulated/0/Music",
            "Café del Mar",
            "日本のロック",
            "A  double  space",
            "Ünïcodé / Sub-Genre",
            "~leading tilde",
            "trailing space "
        )
        values.forEach { value ->
            assertEquals(value, decodeNavArg(encodeNavArg(value)), "round trip failed for: $value")
        }
    }

    @Test
    @DisplayName("Malformed argument decodes to blank instead of throwing")
    fun malformedIsTolerated() {
        assertEquals("", decodeNavArg("~!!!not base64!!!"))
        assertEquals("", decodeNavArg(""))
        assertEquals("", decodeNavArg("~"))
    }

    @Test
    @DisplayName("Folder route round trips a real path")
    fun folderRouteRoundTrips() {
        val path = "/storage/emulated/0/Music/Rock_80s"
        val route = Screen.FolderTracks.createRoute(path)
        val segment = route.removePrefix("library/folder/")
        assertEquals(path, decodeNavArg(segment))
    }
}
