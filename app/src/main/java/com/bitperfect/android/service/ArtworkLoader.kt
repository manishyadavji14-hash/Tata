package com.bitperfect.android.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.bitperfect.android.library.MediaStoreArtwork
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
            val bitmap = decodeFromUri(source.uri)
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

    /**
     * Decode a cover the system holds.
     *
     * Opened through [MediaStoreArtwork] rather than with `openInputStream`. A
     * MediaStore album URI does not serve its cover as a stream, so the direct
     * call failed for every indexed track — which is why covers showed inside the
     * app, where Coil makes the typed-asset call, but never on the lock screen.
     */
    private fun decodeFromUri(artworkUri: String): Bitmap? = try {
        // Two passes: measure, then decode downscaled. Decoding a large cover at
        // full size first would allocate tens of megabytes to immediately shrink.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(artworkUri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        // Reopened rather than reset: a thumbnail stream is not seekable, so the
        // measuring pass has already consumed it.
        openStream(artworkUri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (error: Exception) {
        Log.d(TAG, "Could not decode artwork from $artworkUri: ${error.message}")
        null
    }

    private fun openStream(artworkUri: String) =
        MediaStoreArtwork.openArtworkStream(context.contentResolver, artworkUri)

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

    /**
     * Encode a cover small enough to travel with a session update.
     *
     * Walks [compressLadder] and takes the first attempt that fits. It used to
     * make one attempt and return **null** if the result was too big — silently,
     * with no log and no second try — so a cover that was read and decoded
     * perfectly well simply never reached the lock screen. Combined with
     * [sampleSizeFor] decoding at up to twice the intended edge, that is the most
     * likely reason artwork was missing there while the in-app UI showed it.
     *
     * Degrading beats dropping: a slightly soft cover on the lock screen is worth
     * more than no cover, and a cover that will not fit at a quarter of its size
     * and lowest quality is a real anomaly worth a log line.
     */
    private fun compress(bitmap: Bitmap): ByteArray? {
        for (attempt in compressLadder()) {
            val candidate = try {
                val source = if (attempt.scale == 1) bitmap else scaled(bitmap, attempt.scale)
                // Deliberately not recycled: [bitmap] belongs to the caller and is
                // still handed to the notification as its large icon.
                source?.let { encode(it, attempt.quality) }
            } catch (error: Exception) {
                Log.d(TAG, "Cover encode failed at $attempt: ${error.message}")
                null
            }

            if (candidate != null && candidate.size <= MAX_DATA_BYTES) return candidate
        }

        Log.w(
            TAG,
            "Cover could not be encoded within $MAX_DATA_BYTES bytes " +
                "(${bitmap.width}x${bitmap.height}); the lock screen will have none"
        )
        return null
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        // JPEG, not PNG: a PNG of a photographic cover is several times larger for
        // no visible gain at the size a lock screen shows it.
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun scaled(bitmap: Bitmap, divisor: Int): Bitmap? {
        val width = bitmap.width / divisor
        val height = bitmap.height / divisor
        if (width <= 0 || height <= 0) return null
        return bitmap.scale(width, height)
    }

    internal companion object {
        const val TAG = "ArtworkLoader"

        /**
         * Longest edge of a decoded cover. Comfortably larger than any lock-screen
         * or notification cover, and small enough to stay well inside a Binder
         * transaction once encoded.
         */
        const val MAX_EDGE_PX = 512
        const val JPEG_QUALITY = 85

        /**
         * Hard ceiling on bytes sent with a session update. A full-resolution cover
         * can be several megabytes — enough to blow the transaction limit and take
         * the notification down with it.
         */
        const val MAX_DATA_BYTES = 512 * 1024

        /** Qualities to try before giving up resolution. */
        private val QUALITY_STEPS = listOf(JPEG_QUALITY, 70, 55)

        /** Divisors to try once quality alone is not enough. */
        private val SCALE_STEPS = listOf(1, 2, 4)

        /**
         * Sample size that brings the longest edge to **no more than**
         * [MAX_EDGE_PX].
         *
         * This used to halve only while *both* halved edges were still at least
         * [MAX_EDGE_PX] — the Android documentation's "at least this big" recipe,
         * which is the opposite of a cap. A 1000x1000 cover therefore decoded at
         * its full 1000px, four times the intended pixel count, and a wide cover
         * could not be reduced at all because its short edge blocked the halving.
         * The JPEG made from it then risked exceeding [MAX_DATA_BYTES], and the
         * old [compress] threw the cover away without a word when it did.
         */
        fun sampleSizeFor(width: Int, height: Int): Int {
            if (width <= 0 || height <= 0) return 1

            val longest = maxOf(width, height)
            var sample = 1
            // BitmapFactory rounds inSampleSize down to a power of two, so only
            // powers of two are worth returning. Terminates because `longest` is
            // finite and `sample` doubles every pass.
            while (longest / sample > MAX_EDGE_PX) sample *= 2
            return sample
        }

        /**
         * Attempts to fit a cover inside [MAX_DATA_BYTES], in order.
         *
         * Quality is given up before resolution: at [MAX_EDGE_PX] the cover is
         * already small, and a soft-but-sharp-edged cover reads better on a lock
         * screen than a crisply compressed quarter-size one. After the
         * [sampleSizeFor] fix nothing past the first entry should ever be needed —
         * the ladder exists so that "needed" and "dropped" are different outcomes.
         */
        fun compressLadder(): List<CompressAttempt> = buildList {
            for (scale in SCALE_STEPS) {
                for (quality in QUALITY_STEPS) {
                    add(CompressAttempt(quality = quality, scale = scale))
                }
            }
        }
    }

    /** One encoding attempt: a JPEG quality and a resolution divisor. */
    internal data class CompressAttempt(val quality: Int, val scale: Int)
}
