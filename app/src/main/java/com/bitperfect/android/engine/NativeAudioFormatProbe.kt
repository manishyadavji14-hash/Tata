package com.bitperfect.android.engine

import android.util.Log
import com.bitperfect.android.BitPerfectApp
import com.bitperfect.android.library.scanner.AudioFormatProbe
import com.bitperfect.android.library.scanner.ProbedFormat

/**
 * Reads exact PCM format from a file using the native WAV/FLAC decoders.
 *
 * This exists to fill in what MediaStore cannot report below Android 12. It
 * opens its own decoder session per file and closes it immediately; sessions
 * are independent of playback, so probing never disturbs an active stream.
 *
 * Only formats the native engine can open are attempted. Everything else
 * returns null and the caller keeps whatever the media index provided.
 */
class NativeAudioFormatProbe(
    private val engine: NativeAudioEngine = NativeAudioEngine()
) : AudioFormatProbe {

    companion object {
        private const val TAG = "NativeAudioFormatProbe"

        /** Extensions the native decoders can open. */
        private val PROBEABLE_EXTENSIONS = setOf("wav", "wave", "flac")

        /**
         * Whether a file is worth probing at all.
         */
        fun canProbe(path: String): Boolean =
            path.substringAfterLast('.', "").lowercase() in PROBEABLE_EXTENSIONS
    }

    override fun probe(path: String): ProbedFormat? {
        if (!BitPerfectApp.isNativeLoaded) return null
        if (!canProbe(path)) return null

        return try {
            engine.openDecoder(path)?.use { session ->
                val format = session.format
                ProbedFormat(
                    sampleRate = format.sampleRate,
                    bitDepth = format.bitsPerSample,
                    channels = format.channels
                ).takeIf { it.isUsable }
            }
        } catch (error: UnsatisfiedLinkError) {
            Log.w(TAG, "Native decoder unavailable while probing $path")
            null
        } catch (error: Exception) {
            Log.w(TAG, "Could not probe $path: ${error.message}")
            null
        }
    }
}
