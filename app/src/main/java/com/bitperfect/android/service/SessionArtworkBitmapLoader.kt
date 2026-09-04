package com.bitperfect.android.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.bitperfect.android.library.MediaStoreArtwork
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Turns what the media session holds into the Bitmap the lock screen draws.
 *
 * media3 does not draw artwork from the bytes handed to `MediaMetadata` directly.
 * It asks a `BitmapLoader` for a Bitmap and only then publishes it to the platform
 * session, which is what SystemUI reads. Left unset, `MediaSession.Builder`
 * installs `CacheBitmapLoader(DataSourceBitmapLoader(context))` — verified in the
 * 1.2.1 bytecode — and that loader reads a URI with `openInputStream`.
 *
 * **That cannot work for this app's artwork URIs.** The scanner records
 * `content://media/external/audio/albums/<id>`, whose cover MediaStore serves only
 * as a typed asset; `openInputStream` on it throws. So the URI branch of media3's
 * default loader was guaranteed to fail here, which made it a fallback in
 * appearance only: whenever the cover bytes were missing for any reason, the URI
 * beside them could never stand in, and the lock screen simply stayed blank.
 *
 * Supplying a loader that shares [MediaStoreArtwork] with the rest of the app makes
 * both branches work and removes the last place where this app and the system
 * disagreed about how to open a cover.
 *
 * `loadBitmapFromMetadata` is inherited: it prefers `artworkData` and falls back to
 * `artworkUri`, which is the order this app wants — bytes are already downscaled
 * and need no MediaStore cooperation.
 */
@UnstableApi
class SessionArtworkBitmapLoader(private val context: Context) : BitmapLoader {

    /**
     * Single thread, because covers are decoded one track at a time and the work is
     * short. Daemon so it can never hold the process up.
     */
    private val executor: ListeningExecutorService = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }
    )

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit(
            Callable {
                BitmapFactory.decodeByteArray(data, 0, data.size)
                    // Deliberately thrown rather than returned as null: media3
                    // reports a failed future, and a silent null here would be
                    // indistinguishable from "this track has no cover".
                    ?: throw IllegalArgumentException("Cover bytes could not be decoded")
            }
        )

    override fun loadBitmap(
        uri: Uri,
        options: BitmapFactory.Options?
    ): ListenableFuture<Bitmap> =
        executor.submit(
            Callable {
                val bitmap = MediaStoreArtwork
                    .openArtworkStream(context.contentResolver, uri.toString())
                    ?.use { BitmapFactory.decodeStream(it, null, options) }

                bitmap ?: throw IllegalArgumentException("No cover could be read from $uri")
            }
        )

    /** Stop the decode thread. Called when the session is released. */
    fun release() {
        runCatching { executor.shutdown() }
            .onFailure { Log.d(TAG, "Could not shut down the artwork decoder: ${it.message}") }
    }

    private companion object {
        const val TAG = "SessionArtworkLoader"
        const val THREAD_NAME = "BitPerfectSessionArtwork"
    }
}
