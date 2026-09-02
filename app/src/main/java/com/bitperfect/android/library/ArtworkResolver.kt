package com.bitperfect.android.library

import android.content.ContentResolver
import android.util.Log
import androidx.core.net.toUri
import java.io.File

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
     * Best displayable cover for [audioPath], or null when there is none.
     *
     * @param storedArtworkPath what the library already has recorded, if anything.
     */
    fun resolve(audioPath: String, storedArtworkPath: String?): String? {
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
    }
}
