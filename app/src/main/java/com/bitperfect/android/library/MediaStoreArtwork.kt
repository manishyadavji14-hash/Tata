package com.bitperfect.android.library

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import java.io.InputStream

/**
 * Opens artwork URIs the way the platform actually serves them.
 *
 * The scanner records `content://media/external/audio/albums/<albumId>` for every
 * indexed track. That URI names a **row in the albums table**, not a file, and
 * MediaStore does not serve its cover as a plain byte stream:
 * `openInputStream` on it throws `FileNotFoundException: No media for album
 * content`. Album art has to be requested as a *typed* asset — which is what the
 * documented `ContentResolver.loadThumbnail` does internally, and what
 * `MediaStore.Audio.Albums.ALBUM_ART` was deprecated in favour of.
 *
 * This existed as a split brain and it is the reason covers appeared in the app
 * but not on the lock screen. Coil, which draws every cover inside the app, does
 * the typed-asset call for exactly these URIs and so succeeded. This app's own two
 * consumers — the resolver deciding whether a URI is worth keeping, and the media
 * session decoding bytes for the notification — both used `openInputStream` and so
 * both failed on the very same URI. The symptoms looked unrelated and neither had
 * anything to do with the file's tags:
 *
 * - the lock screen and notification got no cover at all, because the decode
 *   returned null;
 * - every play re-parsed the audio file looking for an embedded picture, because
 *   the recorded URI could never be judged usable;
 * - the "rebuild album art" report counted those tracks as having no cover, which
 *   is the opposite of the truth.
 *
 * So both consumers go through here now, and [isAlbumArtUri] is deliberately a
 * copy of Coil's own predicate: if the two ever disagreed, a cover would render in
 * one place and not the other, which is the bug this replaces.
 */
object MediaStoreArtwork {

    /**
     * How a given artwork reference has to be opened.
     *
     * Named rather than decided inline so the choice is testable on its own. The
     * bug this replaces was exactly a wrong choice here, and while it was buried
     * inside a `ContentResolver` call no test could have caught it coming back.
     */
    enum class Access {
        /** A MediaStore album cover: a typed asset, not a stream. */
        Thumbnail,

        /** Anything else the resolver can open directly. */
        Stream,

        /** Nothing to open. */
        None
    }

    /** How [artworkUri] must be opened. */
    fun accessFor(artworkUri: String?): Access = when {
        artworkUri?.trim().isNullOrEmpty() -> Access.None
        isAlbumArtUri(artworkUri) -> Access.Thumbnail
        else -> Access.Stream
    }

    /**
     * Whether [value] names a MediaStore album row, whose cover is served as a
     * thumbnail rather than as a stream.
     *
     * Matches Coil 2.x `ContentUriFetcher.isMusicThumbnailUri`: the `media`
     * authority, and the last three path segments ending `audio/albums/<id>`.
     * Compared case-sensitively for the same reason — agreeing with Coil matters
     * more than being liberal, because a disagreement is invisible until a cover
     * is missing from one surface only.
     *
     * Implemented on the string rather than on `android.net.Uri` so the rule can
     * be unit tested; `Uri` is not usable in a plain JVM test.
     */
    fun isAlbumArtUri(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        if (!trimmed.startsWith(MEDIA_PREFIX)) return false

        val path = trimmed.removePrefix(MEDIA_PREFIX)
            .substringBefore('?')
            .substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 3) return false

        return segments[segments.size - 3] == "audio" && segments[segments.size - 2] == "albums"
    }

    /**
     * Open [artworkUri] for reading, or null if it cannot be read.
     *
     * The caller owns the stream and must close it.
     */
    fun openArtworkStream(contentResolver: ContentResolver, artworkUri: String?): InputStream? {
        val value = artworkUri?.trim().orEmpty()
        if (value.isEmpty()) return null

        val uri = runCatching { value.toUri() }.getOrNull() ?: return null

        return try {
            when (accessFor(value)) {
                Access.Thumbnail -> openThumbnail(contentResolver, uri)
                Access.Stream -> contentResolver.openInputStream(uri)
                Access.None -> null
            }
        } catch (error: Exception) {
            // Expected and common: MediaStore keeps an albums row for a group of
            // tracks whether or not it ever managed to extract a cover for them.
            Log.d(TAG, "Could not open artwork $value: ${error.message}")
            null
        }
    }

    /**
     * Request the album cover as a typed asset, the way `loadThumbnail` does.
     *
     * A size hint is required rather than optional: MediaStore uses it to pick or
     * generate a thumbnail, and the call is not reliable without one.
     */
    private fun openThumbnail(contentResolver: ContentResolver, uri: Uri): InputStream? {
        val options = Bundle().apply {
            putParcelable(ContentResolver.EXTRA_SIZE, Point(THUMBNAIL_EDGE_PX, THUMBNAIL_EDGE_PX))
        }

        val descriptor: AssetFileDescriptor =
            contentResolver.openTypedAssetFile(uri, IMAGE_MIME_FILTER, options, null)
                ?: return null

        return try {
            // The returned stream owns the descriptor and closing it closes both.
            descriptor.createInputStream()
        } catch (error: Exception) {
            // Only reached if the stream could not be created, in which case
            // nothing else will ever close the descriptor.
            runCatching { descriptor.close() }
            throw error
        }
    }

    private const val TAG = "MediaStoreArtwork"
    private const val MEDIA_PREFIX = "content://media/"
    private const val IMAGE_MIME_FILTER = "image/*"

    /**
     * Requested thumbnail edge. Matches the ceiling the media session downscales
     * to anyway, so asking for more would only be thrown away.
     */
    private const val THUMBNAIL_EDGE_PX = 512
}
