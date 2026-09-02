package com.bitperfect.android.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Recognising the URIs whose cover MediaStore only serves as a typed asset.
 *
 * Reported as "album art shows in the player but not on the lock screen", and
 * separately as covers never being found for files that plainly had one.
 *
 * Both came from one split: Coil, which draws every cover inside the app, treats
 * `content://media/.../audio/albums/<id>` as a thumbnail and asks for it with
 * `openTypedAssetFile`. This app's own two consumers asked for the same URI with
 * `openInputStream`, which MediaStore refuses for an album row. So the cover
 * rendered in the app and nowhere else, and the resolver judged a perfectly good
 * URI unusable and re-parsed the audio file on every single play.
 *
 * These cases are taken from Coil 2.7.0's `ContentUriFetcher.isMusicThumbnailUri`,
 * verified against its bytecode: the `media` authority, and the last three path
 * segments ending `audio/albums/<id>`, compared case-sensitively. Agreeing with
 * Coil is the whole point — a disagreement would put a cover on one surface and not
 * the other, which is the bug being fixed.
 */
@DisplayName("MediaStoreArtwork Tests")
class MediaStoreArtworkTest {

    // --- The choice that was wrong, stated directly ---

    @Test
    @DisplayName("an album cover is opened as a thumbnail, not as a stream")
    fun albumUriIsOpenedAsAThumbnail() {
        // This is the regression in one line. Opening the scanner's album URI as a
        // stream is what left the lock screen with no cover on every indexed track,
        // and made the resolver re-parse the file on every play.
        assertEquals(
            MediaStoreArtwork.Access.Thumbnail,
            MediaStoreArtwork.accessFor("content://media/external/audio/albums/42")
        )
    }

    @Test
    @DisplayName("a cached cover and a legacy URI are opened as streams")
    fun everythingElseIsAStream() {
        // Asking for a typed asset where a plain stream is served fails just as
        // badly in the other direction, so the narrowness matters both ways.
        assertEquals(
            MediaStoreArtwork.Access.Stream,
            MediaStoreArtwork.accessFor("content://media/external/audio/albumart/42")
        )
        assertEquals(
            MediaStoreArtwork.Access.Stream,
            MediaStoreArtwork.accessFor("/data/user/0/app/cache/artwork/art_a1.img")
        )
    }

    @Test
    @DisplayName("nothing recorded is not opened at all")
    fun nothingRecordedIsNotOpened() {
        for (value in listOf(null, "", "   ")) {
            assertEquals(MediaStoreArtwork.Access.None, MediaStoreArtwork.accessFor(value))
        }
    }

    // --- What the scanner actually records ---

    @Test
    @DisplayName("the album URI the scanner records is recognised")
    fun canonicalAlbumUri() {
        // MediaStoreAudioSource builds exactly this, from
        // ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, id).
        assertTrue(MediaStoreArtwork.isAlbumArtUri("content://media/external/audio/albums/42"))
    }

    @Test
    @DisplayName("an album URI with no volume segment is recognised")
    fun albumUriWithoutVolume() {
        // Coil tests the last three segments, not absolute positions, so a URI with
        // no volume still matches. Mirrored so the two never diverge.
        assertTrue(MediaStoreArtwork.isAlbumArtUri("content://media/audio/albums/42"))
    }

    @Test
    @DisplayName("a query or fragment does not hide the album path")
    fun queryAndFragmentIgnored() {
        assertTrue(MediaStoreArtwork.isAlbumArtUri("content://media/external/audio/albums/42?x=1"))
        assertTrue(MediaStoreArtwork.isAlbumArtUri("content://media/external/audio/albums/42#f"))
    }

    @Test
    @DisplayName("surrounding whitespace does not hide the album path")
    fun whitespaceTolerated() {
        assertTrue(MediaStoreArtwork.isAlbumArtUri("  content://media/external/audio/albums/42  "))
    }

    // --- What must not be mistaken for it ---

    @Test
    @DisplayName("the legacy albumart URI is not an album URI")
    fun legacyAlbumArtUriIsNotAnAlbumUri() {
        // The deprecated pre-scoped-storage table. It is a plain stream, so it must
        // keep going through openInputStream; asking for a typed asset would fail.
        assertFalse(MediaStoreArtwork.isAlbumArtUri("content://media/external/audio/albumart/42"))
    }

    @Test
    @DisplayName("a track URI is not an album URI")
    fun trackUriIsNotAnAlbumUri() {
        assertFalse(MediaStoreArtwork.isAlbumArtUri("content://media/external/audio/media/42"))
    }

    @Test
    @DisplayName("the albums collection with no id is not an album URI")
    fun collectionUriIsNotAnAlbumUri() {
        // No id means no particular cover to ask for.
        assertFalse(MediaStoreArtwork.isAlbumArtUri("content://media/external/audio/albums"))
    }

    @Test
    @DisplayName("an images URI is not an album URI")
    fun imagesUriIsNotAnAlbumUri() {
        assertFalse(MediaStoreArtwork.isAlbumArtUri("content://media/external/images/media/42"))
    }

    @Test
    @DisplayName("another provider's lookalike path is not an album URI")
    fun foreignAuthorityRejected() {
        // The authority is part of the rule: only MediaStore serves album art this
        // way, and a document provider's identical-looking path does not.
        assertFalse(MediaStoreArtwork.isAlbumArtUri("content://com.other/external/audio/albums/42"))
    }

    @Test
    @DisplayName("segment matching is case-sensitive, as Coil's is")
    fun caseSensitive() {
        // Being more liberal than Coil would mean claiming a URI is a thumbnail that
        // Coil will try to open as a stream — a cover on the lock screen and a
        // placeholder in the app.
        assertFalse(MediaStoreArtwork.isAlbumArtUri("content://media/external/AUDIO/ALBUMS/42"))
    }

    @Test
    @DisplayName("a cached cover path is not an album URI")
    fun cachePathIsNotAnAlbumUri() {
        assertFalse(MediaStoreArtwork.isAlbumArtUri("/data/user/0/app/cache/artwork/art_a1.img"))
    }

    @Test
    @DisplayName("nothing recorded is not an album URI")
    fun blankIsNotAnAlbumUri() {
        for (value in listOf(null, "", "   ")) {
            assertFalse(MediaStoreArtwork.isAlbumArtUri(value), "value='$value'")
        }
    }
}
