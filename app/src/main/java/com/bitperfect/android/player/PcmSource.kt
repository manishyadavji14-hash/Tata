package com.bitperfect.android.player

import java.nio.ByteBuffer

/**
 * A source of decoded PCM frames.
 *
 * There are two implementations, and the difference matters:
 *
 *  - [NativePcmSource] uses the engine's own WAV/FLAC decoders. The samples are
 *    exactly what is in the file, which is what the USB path requires.
 *  - [MediaCodecPcmSource] uses the platform decoders, which covers the formats
 *    the native engine has no decoder for (Opus, MP3, AAC, Vorbis, M4A) and is
 *    also more robust for FLAC.
 *
 * Both are pull-based: the playback worker asks for frames and blocks on the
 * output, so neither owns a thread.
 */
interface PcmSource : AutoCloseable {

    val sampleRate: Int
    val channels: Int

    /** Bits per sample of the PCM this source emits, not necessarily the file's. */
    val bitsPerSample: Int

    /** Total frames, or 0 when the container does not say. */
    val totalFrames: Long

    /** Short name of the decoder, for display. */
    val codecName: String

    /**
     * True when the emitted samples are bit-for-bit what the file holds.
     * False when the platform decoder may have converted them.
     */
    val isExact: Boolean

    val bytesPerFrame: Int
        get() = (bitsPerSample / 8) * channels

    val durationMs: Long
        get() = if (sampleRate > 0) {
            (totalFrames / sampleRate) * 1000L + (totalFrames % sampleRate) * 1000L / sampleRate
        } else {
            0L
        }

    /**
     * Decode into [out], up to [maxFrames].
     *
     * @return frames written, 0 at end of stream, or -1 on a decode error.
     */
    fun read(out: ByteBuffer, maxFrames: Int): Int

    /**
     * Seek to [frame].
     * @return the frame actually reached, or -1 if seeking failed.
     */
    fun seek(frame: Long): Long
}
