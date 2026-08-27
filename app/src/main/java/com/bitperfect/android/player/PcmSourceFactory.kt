package com.bitperfect.android.player

import android.util.Log
import com.bitperfect.android.engine.NativeAudioEngine

/**
 * Chooses which decoder opens a file.
 *
 * The choice differs by output, and deliberately so:
 *
 *  - **USB** needs the native decoders, because the whole point of that path is
 *    that samples reach the DAC unaltered. A format the native engine cannot read
 *    is an error there rather than something to silently work around.
 *  - **Android output** goes through the platform codecs for everything except
 *    WAV. That path is not bit-perfect anyway — Android may resample or mix it —
 *    so correctness and format coverage matter more than exactness.
 */
object PcmSourceFactory {

    private const val TAG = "PcmSourceFactory"

    /** Every extension that can be played by one decoder or the other. */
    val PLAYABLE_EXTENSIONS: Set<String> =
        NativePcmSource.SUPPORTED_EXTENSIONS + MediaCodecPcmSource.SUPPORTED_EXTENSIONS

    fun isPlayable(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in PLAYABLE_EXTENSIONS

    /**
     * Open [path] for the Android output path.
     *
     * WAV uses the native reader, which is exact and needs no codec. Everything
     * else, FLAC included, uses the platform decoder, then falls back to the
     * native one so a file is never rejected while a decoder for it exists.
     */
    fun openForAndroidOutput(engine: NativeAudioEngine, path: String): PcmSource? {
        val extension = path.substringAfterLast('.', "").lowercase()

        if (extension == "wav" || extension == "wave") {
            NativePcmSource.open(engine, path)?.let { return it }
        }

        if (MediaCodecPcmSource.canOpen(path)) {
            MediaCodecPcmSource.open(path)?.let { return it }
            Log.w(TAG, "Platform decoder could not open $path; trying the native decoder")
        }

        if (NativePcmSource.canOpen(path)) {
            NativePcmSource.open(engine, path)?.let { return it }
        }

        // Last resort: let the platform try regardless of extension, since the
        // container often says more than the file name does.
        return MediaCodecPcmSource.open(path)
    }

    /**
     * Open [path] for the USB path. Native decoders only, so the stream stays
     * bit-perfect; returns null when there is no exact decoder for the format.
     */
    fun openForUsbOutput(engine: NativeAudioEngine, path: String): PcmSource? {
        if (!NativePcmSource.canOpen(path)) return null
        return NativePcmSource.open(engine, path)
    }
}
