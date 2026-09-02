package com.bitperfect.android.library

import android.content.ContentResolver
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a cover for a track that can actually be displayed.
 *
 * The scanner records whatever MediaStore offers, which is
 * `content://media/external/audio/albumart/<albumId>`. That URI is a leftover
 * from the pre-scoped-storage `albumart` table: it was deprecated in Android 10
 * and on current versions it usually resolves to nothing at all. Everything
 * downstream failed silently on it — Coil fell back to a placeholder icon and the
 * media session got no bytes — so the app looked like it simply had no artwork,
 * even for files with a cover embedded in them.
 *
 * The order here is therefore "most reliable first":
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
        canOpenUri = { uri ->
            try {
                contentResolver.openInputStream(uri.toUri())?.use { true } ?: false
            } catch (error: Exception) {
                // The usual case on modern Android: the albumart row outlives the
                // file it pointed at, or the table is gone entirely.
                false
            }
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
    }
}
