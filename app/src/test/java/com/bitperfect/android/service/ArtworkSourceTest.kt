package com.bitperfect.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Deciding how album art can be handed to the system.
 *
 * The library stores artwork as one nullable string that is really two different
 * things: a MediaStore `content://` URI the system can read, or a path into this
 * app's private cache that it cannot. Getting the distinction wrong produces a
 * silently blank cover on the lock screen — no error, just no art — so the rule is
 * pinned down here.
 */
@DisplayName("ArtworkSource Tests")
class ArtworkSourceTest {

    @Test
    @DisplayName("a MediaStore album-art URI is handed to the system as a URI")
    fun mediaStoreUri() {
        val uri = "content://media/external/audio/albums/42"

        assertEquals(ArtworkSource.SystemReadableUri(uri), ArtworkSource.of(uri))
    }

    @Test
    @DisplayName("other system-resolvable schemes are passed through too")
    fun otherResolvableSchemes() {
        for (uri in listOf(
            "content://media/external/audio/albums/1",
            "android.resource://com.bitperfect.android/drawable/art",
            "https://example.com/cover.jpg",
            "http://example.com/cover.jpg"
        )) {
            assertEquals(
                ArtworkSource.SystemReadableUri(uri),
                ArtworkSource.of(uri),
                "expected $uri to be handed over as a URI"
            )
        }
    }

    @Test
    @DisplayName("schemes are matched case-insensitively")
    fun schemeCaseInsensitive() {
        assertEquals(
            ArtworkSource.SystemReadableUri("CONTENT://media/external/audio/albums/7"),
            ArtworkSource.of("CONTENT://media/external/audio/albums/7")
        )
    }

    @Test
    @DisplayName("a cached file path is read by this app, not handed over")
    fun cachedFileIsReadLocally() {
        // ArtworkCache writes here. SystemUI cannot open it, so the bytes have to
        // travel with the session update instead of a URI.
        val path = "/data/user/0/com.bitperfect.android/cache/artwork/art_abc123.img"

        assertEquals(ArtworkSource.AppPrivateFile(path), ArtworkSource.of(path))
    }

    @Test
    @DisplayName("a file:// URI is treated as a private file, not as system-readable")
    fun fileUriIsNotSystemReadable() {
        // The trap: file:// looks like a URI, so it is tempting to forward it. The
        // only files this app points at are in its private cache, so forwarding
        // one produces a blank cover with no error anywhere.
        val path = "/data/user/0/com.bitperfect.android/cache/artwork/art_abc123.img"

        assertEquals(ArtworkSource.AppPrivateFile(path), ArtworkSource.of("file://$path"))
    }

    @Test
    @DisplayName("no artwork recorded gives None")
    fun noArtwork() {
        for (value in listOf(null, "", "   ", "\t")) {
            assertEquals(ArtworkSource.None, ArtworkSource.of(value), "value='$value'")
        }
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    fun trimsWhitespace() {
        val uri = "content://media/external/audio/albums/9"

        assertEquals(ArtworkSource.SystemReadableUri(uri), ArtworkSource.of("  $uri  "))
    }

    @Test
    @DisplayName("an unrecognised value gives None rather than a guess")
    fun unrecognisedValue() {
        // A relative path or a scheme nothing can resolve. Better to show no cover
        // than to hand the system something it will fail on.
        for (value in listOf(
            "artwork/art_1.img",
            "not a path at all",
            "ftp://example.com/cover.jpg",
            "file://relative/path"
        )) {
            assertEquals(ArtworkSource.None, ArtworkSource.of(value), "value='$value'")
        }
    }
}
