package com.bitperfect.android.player

import com.bitperfect.android.engine.NativeAudioEngine
import java.nio.ByteBuffer

/**
 * Wraps a native engine decoder session as a [PcmSource].
 *
 * The samples are exactly what the file holds, with no conversion, which is what
 * the USB path needs to stay bit-perfect.
 */
class NativePcmSource private constructor(
    private val session: NativeAudioEngine.DecoderSession,
    override val codecName: String
) : PcmSource {

    private val format = session.format

    override val sampleRate: Int get() = format.sampleRate
    override val channels: Int get() = format.channels
    override val bitsPerSample: Int get() = format.bitsPerSample
    override val totalFrames: Long get() = format.totalFrames
    override val isExact: Boolean = true

    companion object {
        /** Formats the native decoders handle. */
        val SUPPORTED_EXTENSIONS = setOf("wav", "wave", "flac")

        fun canOpen(path: String): Boolean =
            path.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

        fun open(engine: NativeAudioEngine, path: String): NativePcmSource? {
            val session = engine.openDecoder(path) ?: return null
            val name = when (path.substringAfterLast('.', "").lowercase()) {
                "flac" -> "FLAC"
                "wav", "wave" -> "WAV"
                else -> "PCM"
            }
            return NativePcmSource(session, name)
        }
    }

    override fun read(out: ByteBuffer, maxFrames: Int): Int = session.read(out, maxFrames)

    override fun seek(frame: Long): Long = session.seek(frame)

    override fun close() = session.close()
}
