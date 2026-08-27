package com.bitperfect.android.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes with the platform codecs, covering the formats the native engine has
 * no decoder for: Opus, MP3, AAC, Vorbis, M4A/ALAC.
 *
 * FLAC is routed here too. The native FLAC decoder exists but is not trusted for
 * playback yet: its own header notes LPC subframes as unsupported, and every one
 * of its unit tests covers STREAMINFO parsing or synthetic frames rather than a
 * real encoded file, so nothing ever proved it could decode one. Android's FLAC
 * decoder is lossless, so nothing is given up by using it here.
 *
 * Output is always 16-bit signed little-endian PCM. That is what AudioTrack
 * supports everywhere, whereas 24-bit packed output is patchily supported and was
 * a likely cause of hi-res files producing no sound.
 *
 * Not thread-safe; the playback worker owns the instance.
 */
class MediaCodecPcmSource private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    override val sampleRate: Int,
    override val channels: Int,
    override val totalFrames: Long,
    override val codecName: String
) : PcmSource {

    override val bitsPerSample: Int = 16

    /** The platform decoder may resample or convert, so this is never exact. */
    override val isExact: Boolean = false

    private val bufferInfo = MediaCodec.BufferInfo()

    /** Decoded PCM not yet handed to the caller. */
    private var pending: ByteBuffer = ByteBuffer.allocate(0)

    private var inputExhausted = false
    private var outputExhausted = false
    private var framesEmitted = 0L

    companion object {
        private const val TAG = "MediaCodecPcmSource"
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /**
         * Extensions handled here. WAV is deliberately absent: the native decoder
         * reads it exactly and needs no platform codec.
         */
        val SUPPORTED_EXTENSIONS = setOf(
            "flac", "mp3", "aac", "m4a", "mp4", "ogg", "oga", "opus", "alac", "3gp", "aiff", "aif"
        )

        fun canOpen(path: String): Boolean =
            path.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

        /**
         * Open [path], or return null when there is no usable audio track or no
         * decoder for it.
         */
        fun open(path: String): MediaCodecPcmSource? {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(path)

                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(i)
                    val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = candidate
                        break
                    }
                }
                if (trackIndex < 0 || format == null) {
                    Log.w(TAG, "No audio track in $path")
                    extractor.release()
                    return null
                }

                extractor.selectTrack(trackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }

                // Ask for 16-bit even where the codec would default to float.
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val totalFrames = if (durationUs > 0 && sampleRate > 0) {
                    (durationUs * sampleRate) / 1_000_000L
                } else {
                    0L
                }

                return MediaCodecPcmSource(
                    extractor = extractor,
                    codec = codec,
                    sampleRate = sampleRate,
                    channels = channels,
                    totalFrames = totalFrames,
                    codecName = displayNameFor(mime, path)
                )
            } catch (error: Exception) {
                Log.w(TAG, "Could not open $path: ${error.message}")
                try {
                    codec?.stop()
                    codec?.release()
                } catch (_: Exception) {
                    // Already torn down.
                }
                extractor.release()
                return null
            }
        }

        private fun displayNameFor(mime: String, path: String): String = when {
            mime.contains("flac") -> "FLAC"
            mime.contains("opus") -> "Opus"
            mime.contains("vorbis") -> "Vorbis"
            mime.contains("mpeg") -> "MP3"
            mime.contains("mp4a") || mime.contains("aac") -> "AAC"
            mime.contains("alac") -> "ALAC"
            else -> path.substringAfterLast('.', "PCM").uppercase()
        }
    }

    override fun read(out: ByteBuffer, maxFrames: Int): Int {
        val wanted = maxFrames * bytesPerFrame
        if (wanted <= 0) return 0

        out.clear()

        try {
            // Top up from the codec until there is something to give back, or the
            // stream is genuinely finished.
            while (!pending.hasRemaining() && !outputExhausted) {
                feedInput()
                if (!drainOutput()) {
                    // No output available yet and not at EOS: try again rather
                    // than reporting a false end of stream.
                    continue
                }
            }
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Decoder error: ${error.message}")
            return -1
        } catch (error: MediaCodec.CodecException) {
            Log.w(TAG, "Codec exception: ${error.message}")
            return -1
        }

        if (!pending.hasRemaining()) return 0

        val toCopy = minOf(wanted, pending.remaining())
            // Never hand back a partial frame: the caller converts and writes in
            // whole frames and a split one would shift every channel afterwards.
            .let { it - (it % bytesPerFrame) }
        if (toCopy <= 0) return 0

        val slice = pending.slice()
        slice.limit(toCopy)
        out.put(slice)
        pending.position(pending.position() + toCopy)
        out.flip()

        val frames = toCopy / bytesPerFrame
        framesEmitted += frames
        return frames
    }

    private fun feedInput() {
        if (inputExhausted) return

        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return

        val buffer = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputExhausted = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /**
     * @return true when the codec produced output or signalled end of stream,
     *   false when nothing was available this time round.
     */
    private fun drainOutput(): Boolean {
        val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
        when {
            index >= 0 -> {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && bufferInfo.size > 0) {
                    val copy = ByteBuffer.allocate(bufferInfo.size).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                    copy.put(buffer)
                    copy.flip()
                    pending = copy
                }
                codec.releaseOutputBuffer(index, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputExhausted = true
                }
                return true
            }

            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // Normal at the start of a stream.
                Log.d(TAG, "Output format: ${codec.outputFormat}")
                return false
            }

            index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // If input is finished and the codec has stopped producing, the
                // stream is over. Without this a truncated or malformed file
                // would spin here for ever.
                if (inputExhausted) {
                    outputExhausted = true
                    return true
                }
                return false
            }

            else -> return false
        }
    }

    override fun seek(frame: Long): Long {
        if (sampleRate <= 0) return -1
        val targetUs = (frame * 1_000_000L) / sampleRate
        return try {
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec.flush()
            // No start() here. In synchronous mode the codec resumes simply by
            // dequeuing input buffers again; calling start() after flush() throws
            // IllegalStateException on most devices, which is what made every
            // seek fail with "Could not seek in this file".
            pending = ByteBuffer.allocate(0)
            inputExhausted = false
            outputExhausted = false

            // Report where the extractor actually landed, which for a
            // compressed format is the nearest sync point, not the exact frame.
            val actualUs = extractor.sampleTime.takeIf { it >= 0 } ?: targetUs
            val actualFrame = (actualUs * sampleRate) / 1_000_000L
            framesEmitted = actualFrame
            actualFrame
        } catch (error: Exception) {
            Log.w(TAG, "Seek failed: ${error.message}")
            -1
        }
    }

    override fun close() {
        try {
            codec.stop()
        } catch (_: Exception) {
            // Already stopped.
        }
        try {
            codec.release()
        } catch (_: Exception) {
            // Already released.
        }
        try {
            extractor.release()
        } catch (_: Exception) {
            // Already released.
        }
    }
}
