package com.bitperfect.android.library

import android.content.ContentResolver
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a cover for a track that can actually be displayed.
 *
 * The scanner records whatever MediaStore offers, which is
 * `content://media/external/audio/albums/<albumId>`. That URI is real and current,
 * but it names a row rather than a file, and its cover is only served as a typed
 * asset — see [MediaStoreArtwork]. Probing it with `openInputStream`, as this class
 * originally did, fails on every device, so a perfectly good cover was judged
 * unusable and the file was re-parsed on every play looking for a replacement.
 *
 * The order here is "most reliable first":
 *
 * 1. a cover already extracted into the app's cache, if the file is still there;
 * 2. the cover embedded in the audio file, extracted and cached — this is exact,
 *    needs no MediaStore cooperation, and is what desktop taggers write;
 * 3. the MediaStore URI, but only if it can actually be opened.
 *
 * Resolved paths are handed back so the caller can write them into the library
 * row, which turns this into a one-off cost per file rather than per play.
 */
class ArtworkResolver(
    /**
     * Whether the system can open a URI. Injected so the resolution order can be
     * unit tested; the default asks the real ContentResolver.
     */
    private val canOpenUri: (String) -> Boolean,
    /**
     * Extracts and caches the cover embedded in an audio file, returning its
     * cached path. Injected for the same reason — the real one needs
     * MediaMetadataRetriever.
     */
    private val extractEmbedded: (String) -> String?
) {

    constructor(
        contentResolver: ContentResolver,
        metadataExtractor: MetadataExtractor,
        artworkCache: ArtworkCache
    ) : this(
        // Probed through the shared opener, so "this URI is worth keeping" is
        // decided by the same call that will later be asked to read it. Probing it
        // any other way is how a usable cover came to be treated as missing.
        canOpenUri = { uri ->
            MediaStoreArtwork.openArtworkStream(contentResolver, uri)?.use { true } ?: false
        },
        extractEmbedded = { path ->
            try {
                metadataExtractor.extractArtwork(path, artworkCache)
            } catch (error: Exception) {
                // A file that cannot be opened is not worth failing a scan over.
                Log.d(TAG, "No embedded artwork for $path: ${error.message}")
                null
            }
        }
    )

    /**
     * Locks held while resolving a given file, one per path.
     *
     * A track change asks for the same cover twice at once — the player wants it
     * for the screen, the playback service for the notification — and both would
     * otherwise open the file and decode the picture. Serialising per path means
     * the second caller waits and then finds the first one's cached result, so the
     * work happens once. Per path rather than globally, so an unrelated track does
     * not queue behind it.
     */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Best displayable cover for [audioPath], or null when there is none.
     *
     * @param storedArtworkPath what the library already has recorded, if anything.
     */
    fun resolve(audioPath: String, storedArtworkPath: String?): String? =
        synchronized(locks.getOrPut(audioPath) { Any() }) {
            resolveLocked(audioPath, storedArtworkPath)
        }

    private fun resolveLocked(audioPath: String, storedArtworkPath: String?): String? {
        val stored = storedArtworkPath?.trim().orEmpty()

        // 1. Already extracted and still cached. Cheapest, and stable.
        if (stored.startsWith("/") && File(stored).let { it.isFile && it.length() > 0 }) {
            return stored
        }

        // 2. The file's own embedded cover. Preferred over MediaStore because it
        //    is the actual art the user tagged, and because it does not depend on
        //    a media index that may never have generated a thumbnail.
        embeddedArtwork(audioPath)?.let { return it }

        // 3. Fall back to MediaStore, but only once it is known to open. Returning
        //    an unopenable URI is what produced the blank covers.
        if (stored.isNotEmpty() && stored.startsWith("content://") && canOpen(stored)) {
            return stored
        }

        return null
    }

    /**
     * Whether [storedArtworkPath] is still worth keeping as-is.
     *
     * Lets a caller skip the work in [resolve] for rows that are already good.
     */
    fun isUsable(storedArtworkPath: String?): Boolean {
        val stored = storedArtworkPath?.trim().orEmpty()
        return when {
            stored.isEmpty() -> false
            stored.startsWith("/") -> File(stored).let { it.isFile && it.length() > 0 }
            stored.startsWith("content://") -> canOpen(stored)
            else -> false
        }
    }

    private fun embeddedArtwork(audioPath: String): String? =
        extractEmbedded(audioPath)?.takeIf { it.isNotBlank() }

    private fun canOpen(uri: String): Boolean = canOpenUri(uri)

    internal companion object {
        const val TAG = "ArtworkResolver"

        /**
         * Whether a resolved cover is worth writing over what is already stored.
         *
         * The rule that matters: **a repair never replaces something with nothing.**
         * Writing null erases the recorded MediaStore URI, and that cannot be undone
         * without a rescan — the album id it was built from is not stored on the
         * row. "Could not resolve it now" is not the same as "it is wrong": a
         * content URI can fail to open for a moment and work later, so overwriting
         * it with null makes the track permanently coverless.
         *
         * This shipped the other way round once and wiped artwork across a whole
         * library in a single background pass, so it lives in one place with tests.
         *
         * Deliberately a predicate rather than "the value to write, or null to
         * skip": with that signature null meant both "skip" and "write null", so no
         * test could tell the two apart — and the first attempt at this guard was
         * therefore unable to catch the very bug it was written for.
         */
        fun shouldWriteArtwork(stored: String?, resolved: String?): Boolean =
            resolved != null && resolved != stored

        /**
         * Which cover a rescan should keep.
         *
         * A scan writes the whole row, so without this the value it happens to
         * carry wins outright — and [shouldWriteArtwork] does not protect this
         * path, because it guards the repair passes rather than the scanner.
         *
         * Two orderings matter:
         *
         * - a cover the scan just extracted wins, because it was read from the
         *   file as it is now. The cache key includes the file's size and mtime, so
         *   a re-tagged file genuinely has a new cover;
         * - otherwise an extracted cover already on disk is never downgraded to a
         *   MediaStore URI or to nothing. Extraction can fail for a moment — an
         *   unreadable file, a cache directory that could not be created — and
         *   "the scan found nothing this time" must not erase a working cover.
         *   This is the same rule as [shouldWriteArtwork], applied to the scanner.
         *
         * @param isExtractedCoverPresent whether a cached cover is still on disk.
         *   Injected because `cacheDir` can be cleared by the system at any time,
         *   so a stored path is not evidence the file exists.
         */
        fun preferredArtwork(
            stored: String?,
            scanned: String?,
            isExtractedCoverPresent: (String) -> Boolean = ::isCoverFilePresent
        ): String? {
            val storedValue = stored?.trim().orEmpty()
            val scannedValue = scanned?.trim().orEmpty()

            if (scannedValue.startsWith("/")) return scannedValue

            if (storedValue.startsWith("/") && isExtractedCoverPresent(storedValue)) {
                return storedValue
            }

            return scannedValue.takeIf { it.isNotEmpty() }
                ?: storedValue.takeIf { it.isNotEmpty() }
        }

        private fun isCoverFilePresent(path: String): Boolean =
            File(path).let { it.isFile && it.length() > 0 }
    }
}
