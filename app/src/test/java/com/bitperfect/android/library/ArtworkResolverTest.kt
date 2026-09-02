package com.bitperfect.android.library

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Choosing a cover that can actually be displayed.
 *
 * Reported as "album art is still not showing". The scanner records
 * `content://media/external/audio/albums/<albumId>`, and MediaStore may hold no
 * cover for that album at all — it keeps a row per album whether or not it ever
 * extracted one. Everything downstream failed silently in that case: Coil showed
 * its placeholder and the media session got no bytes, so the app looked like it had
 * no artwork, even for files with a cover inside them.
 *
 * The rule these tests pin down is "prefer what can be read": a cached extraction,
 * then the file's own embedded cover, and the MediaStore URI only once it is known
 * to open. What "known to open" means is [MediaStoreArtwork]'s business, and getting
 * it wrong here is what made a working URI look dead — see MediaStoreArtworkTest.
 */
@DisplayName("ArtworkResolver Tests")
class ArtworkResolverTest {

    private val audioPath = "/storage/emulated/0/Music/song.flac"
    private val albumArtUri = "content://media/external/audio/albums/42"

    /** A resolver with no working MediaStore and no embedded art, by default. */
    private fun resolver(
        openableUris: Set<String> = emptySet(),
        embedded: String? = null,
        onExtract: (String) -> Unit = {}
    ) = ArtworkResolver(
        canOpenUri = { it in openableUris },
        extractEmbedded = { path -> onExtract(path); embedded }
    )

    private fun cachedCover(directory: File, name: String = "art_1.img"): String =
        File(directory, name).apply { writeBytes(ByteArray(64) { 1 }) }.absolutePath

    // --- The reported bug ---

    @Test
    @DisplayName("a dead MediaStore album-art URI falls back to the embedded cover")
    fun deadUriFallsBackToEmbedded(@TempDir directory: File) {
        val cover = cachedCover(directory)
        val resolver = resolver(openableUris = emptySet(), embedded = cover)

        assertEquals(cover, resolver.resolve(audioPath, albumArtUri))
    }

    @Test
    @DisplayName("a dead URI with no embedded cover resolves to nothing, not to itself")
    fun deadUriWithNoEmbeddedArtGivesNull() {
        // Returning the URI anyway is what produced blank covers with no error.
        val resolver = resolver(openableUris = emptySet(), embedded = null)

        assertNull(resolver.resolve(audioPath, albumArtUri))
    }

    @Test
    @DisplayName("the embedded cover is preferred over a working MediaStore URI")
    fun embeddedPreferredOverMediaStore(@TempDir directory: File) {
        // The embedded picture is the art the user actually tagged, and needs no
        // cooperation from the media index.
        val cover = cachedCover(directory)
        val resolver = resolver(openableUris = setOf(albumArtUri), embedded = cover)

        assertEquals(cover, resolver.resolve(audioPath, albumArtUri))
    }

    @Test
    @DisplayName("a working MediaStore URI is used when there is no embedded cover")
    fun mediaStoreUsedWhenNoEmbeddedArt() {
        val resolver = resolver(openableUris = setOf(albumArtUri), embedded = null)

        assertEquals(albumArtUri, resolver.resolve(audioPath, albumArtUri))
    }

    // --- The cheap path ---

    @Test
    @DisplayName("an existing cached cover is reused without re-reading the file")
    fun cachedCoverIsReused(@TempDir directory: File) {
        var extractions = 0
        val cover = cachedCover(directory)
        val resolver = resolver(embedded = cover, onExtract = { extractions++ })

        assertEquals(cover, resolver.resolve(audioPath, cover))
        assertEquals(0, extractions, "a cached cover must not trigger an extraction")
    }

    @Test
    @DisplayName("a cached cover that has been deleted is extracted again")
    fun missingCachedCoverIsReExtracted(@TempDir directory: File) {
        // The artwork cache is in cacheDir, which Android may clear at any time.
        val stale = File(directory, "art_gone.img").absolutePath
        val fresh = cachedCover(directory, "art_fresh.img")
        val resolver = resolver(embedded = fresh)

        assertEquals(fresh, resolver.resolve(audioPath, stale))
    }

    @Test
    @DisplayName("an empty cached file is not treated as a cover")
    fun emptyCachedFileIsRejected(@TempDir directory: File) {
        val empty = File(directory, "art_empty.img").apply { writeBytes(ByteArray(0)) }
        val resolver = resolver(embedded = null)

        assertNull(resolver.resolve(audioPath, empty.absolutePath))
    }

    // --- No stored artwork at all ---

    @Test
    @DisplayName("with nothing recorded the embedded cover is still found")
    fun noStoredArtworkStillExtracts(@TempDir directory: File) {
        val cover = cachedCover(directory)
        val resolver = resolver(embedded = cover)

        assertEquals(cover, resolver.resolve(audioPath, null))
        assertEquals(cover, resolver.resolve(audioPath, ""))
    }

    @Test
    @DisplayName("a blank extraction result counts as no cover")
    fun blankExtractionIsNoCover() {
        val resolver = resolver(embedded = "   ")

        assertNull(resolver.resolve(audioPath, null))
    }

    // --- isUsable, which decides whether the work is needed at all ---

    @Test
    @DisplayName("a present cached file is usable")
    fun usableCachedFile(@TempDir directory: File) {
        assertTrue(resolver().isUsable(cachedCover(directory)))
    }

    @Test
    @DisplayName("a dead URI is not usable, a working one is")
    fun usabilityOfUris() {
        assertFalse(resolver(openableUris = emptySet()).isUsable(albumArtUri))
        assertTrue(resolver(openableUris = setOf(albumArtUri)).isUsable(albumArtUri))
    }

    @Test
    @DisplayName("nothing recorded is not usable")
    fun blankIsNotUsable() {
        for (value in listOf(null, "", "  ")) {
            assertFalse(resolver().isUsable(value), "value='$value'")
        }
    }

    @Test
    @DisplayName("an unrecognised value is not usable")
    fun unrecognisedIsNotUsable() {
        assertFalse(resolver().isUsable("artwork/relative.img"))
    }

    // --- What gets written back, which is where a repair can do damage ---

    @Test
    @DisplayName("finding nothing never overwrites what is stored")
    fun neverWritesNullOverAStoredUri() {
        // The regression this guards: the repair passes wrote whatever they
        // resolved, including null, which erased the recorded MediaStore URI. That
        // cannot be undone without a rescan, because the album id it was built from
        // is not stored on the row — so one background pass over a library could
        // wipe every artwork reference it failed to probe.
        assertFalse(ArtworkResolver.shouldWriteArtwork(albumArtUri, null))
        assertFalse(ArtworkResolver.shouldWriteArtwork("/cache/artwork/art_1.img", null))
    }

    @Test
    @DisplayName("an improvement is written")
    fun writesAnImprovement() {
        val cover = "/cache/artwork/art_1.img"

        assertTrue(ArtworkResolver.shouldWriteArtwork(albumArtUri, cover))
        assertTrue(ArtworkResolver.shouldWriteArtwork(null, cover))
    }

    @Test
    @DisplayName("an unchanged value is not rewritten")
    fun skipsRedundantWrites() {
        assertFalse(ArtworkResolver.shouldWriteArtwork(albumArtUri, albumArtUri))
    }

    @Test
    @DisplayName("nothing stored and nothing found writes nothing")
    fun nothingToDo() {
        assertFalse(ArtworkResolver.shouldWriteArtwork(null, null))
    }

    // --- What a rescan keeps, which is the other way a cover can be lost ---

    private val presentCover: (String) -> Boolean = { true }
    private val clearedCover: (String) -> Boolean = { false }

    @Test
    @DisplayName("a rescan does not downgrade an extracted cover to a MediaStore URI")
    fun rescanKeepsExtractedCover() {
        // The scan writes the whole row, so whatever it carries wins unless this
        // says otherwise — and shouldWriteArtwork does not guard the scanner. If
        // extraction fails for a moment mid-scan, the scanned row carries only the
        // album URI, and without this the working cover would be replaced by it.
        val cover = "/cache/artwork/art_1.img"

        assertEquals(
            cover,
            ArtworkResolver.preferredArtwork(cover, albumArtUri, presentCover)
        )
    }

    @Test
    @DisplayName("a rescan never erases a cover by finding nothing")
    fun rescanNeverErases() {
        val cover = "/cache/artwork/art_1.img"

        assertEquals(cover, ArtworkResolver.preferredArtwork(cover, null, presentCover))
        // Same rule for a recorded URI: losing it costs a rescan to get back,
        // because the album id it was built from is not stored on the row.
        assertEquals(albumArtUri, ArtworkResolver.preferredArtwork(albumArtUri, null, presentCover))
        assertEquals(albumArtUri, ArtworkResolver.preferredArtwork(albumArtUri, "", presentCover))
    }

    @Test
    @DisplayName("a freshly extracted cover replaces the stored one")
    fun rescanTakesFreshExtraction() {
        // The cache key includes the file's size and mtime, so a re-tagged file
        // really does have a new cover and the old path is stale.
        val old = "/cache/artwork/art_old.img"
        val fresh = "/cache/artwork/art_new.img"

        assertEquals(fresh, ArtworkResolver.preferredArtwork(old, fresh, presentCover))
    }

    @Test
    @DisplayName("a stored cover the system has cleared is not preferred")
    fun rescanDropsClearedCover() {
        // cacheDir can be emptied at any time. Holding on to a path whose file is
        // gone would keep the row broken until something re-resolved it, when the
        // scan is offering a URI that may well work.
        val cleared = "/cache/artwork/art_gone.img"

        assertEquals(
            albumArtUri,
            ArtworkResolver.preferredArtwork(cleared, albumArtUri, clearedCover)
        )
    }

    @Test
    @DisplayName("a scanned URI is taken when nothing was stored")
    fun rescanTakesUriWhenNothingStored() {
        assertEquals(albumArtUri, ArtworkResolver.preferredArtwork(null, albumArtUri, presentCover))
    }

    @Test
    @DisplayName("nothing stored and nothing scanned stays nothing")
    fun rescanWithNothingEitherSide() {
        assertNull(ArtworkResolver.preferredArtwork(null, null, presentCover))
        assertNull(ArtworkResolver.preferredArtwork("", "  ", presentCover))
    }

    @Test
    @DisplayName("resolution reads the audio file it was asked about")
    fun extractsFromTheRightFile() {
        var requested: String? = null
        val resolver = resolver(embedded = null, onExtract = { requested = it })

        resolver.resolve(audioPath, albumArtUri)

        assertEquals(audioPath, requested)
    }
}
