package com.bitperfect.android.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream

/**
 * Turns a stored artwork reference into something the system can display.
 *
 * Two outputs, because the lock screen and the media session want different
 * things and the cover may not be reachable by either:
 *
 * - [Loaded.data] the decoded cover bytes. Always populated when a cover was found,
 *   because media3 prefers these over a URI and this is the only route that works
 *   for artwork in the app's private cache, which SystemUI cannot open.
 * - [Loaded.uri] set only when the system could fetch the image itself, for
 *   consumers that would rather do that than take a copy.
 * - [Loaded.bitmap] for the notification's own large icon, which takes a Bitmap.
 *
 * Everything is size-bounded. A session update is delivered over Binder, and a
 * full-resolution cover PNG can be several megabytes — enough to blow the
 * transaction limit and take the notification down with it.
 */
class ArtworkLoader(private val context: Context) {

    data class Loaded(
        val uri: Uri? = null,
        val data: ByteArray? = null,
        val bitmap: Bitmap? = null
    ) {
        // Data class equality on a ByteArray compares references, which is
        // misleading; nothing needs equality here, so it is made explicit.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun load(artworkPath: String?): Loaded = when (val source = ArtworkSource.of(artworkPath)) {
        is ArtworkSource.None -> Loaded()

        is ArtworkSource.SystemReadableUri -> {
            val uri = runCatching { source.uri.toUri() }.getOrNull()
            val bitmap = uri?.let { decodeFromUri(it) }
            // Both the URI and the bytes, deliberately. media3's
            // BitmapLoader.loadBitmapFromMetadata prefers artworkData and only
            // falls back to artworkUri, and which URI schemes its loader supports
            // varies between versions — content:// in particular is not handled by
            // every one. Decoding here makes the cover appear regardless, and the
            // URI is still passed for anything that would rather fetch it itself.
            Loaded(uri = uri, data = bitmap?.let { compress(it) }, bitmap = bitmap)
        }

        is ArtworkSource.AppPrivateFile -> {
            val bitmap = decodeFromFile(source.path)
            Loaded(data = bitmap?.let { compress(it) }, bitmap = bitmap)
        }
    }

    private fun decodeFromUri(uri: Uri): Bitmap? = try {
        // Two passes: measure, then decode downscaled. Decoding a large cover at
        // full size first would allocate tens of megabytes to immediately shrink.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (error: Exception) {
        // A stale MediaStore album-art URI is common — the row outlives the file.
        Log.d(TAG, "Could not decode artwork from $uri: ${error.message}")
        null
    }

    private fun decodeFromFile(path: String): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        BitmapFactory.decodeFile(path, options)
    } catch (error: Exception) {
        Log.d(TAG, "Could not decode artwork file $path: ${error.message}")
        null
    }

    /** Largest power-of-two reduction that still covers [MAX_EDGE_PX]. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= MAX_EDGE_PX && height / (sample * 2) >= MAX_EDGE_PX) {
            sample *= 2
        }
        return sample
    }

    private fun compress(bitmap: Bitmap): ByteArray? = try {
        val stream = ByteArrayOutputStream()
        // JPEG, not PNG: a PNG of a photographic cover is several times larger for
        // no visible gain at the size a lock screen shows it.
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        stream.toByteArray().takeIf { it.size <= MAX_DATA_BYTES }
    } catch (error: Exception) {
        null
    }

    private companion object {
        const val TAG = "ArtworkLoader"

        /**
         * Comfortably larger than any lock-screen or notification cover, and small
         * enough to stay well inside a Binder transaction.
         */
        const val MAX_EDGE_PX = 512
        const val JPEG_QUALITY = 85

        /**
         * Hard ceiling on bytes sent with a session update. Past this the cover is
         * dropped rather than risking a failed transaction that would take the
         * whole notification with it.
         */
        const val MAX_DATA_BYTES = 512 * 1024
    }
}
