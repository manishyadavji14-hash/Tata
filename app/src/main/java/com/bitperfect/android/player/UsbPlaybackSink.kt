package com.bitperfect.android.player

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.bitperfect.android.engine.NativeAudioEngine
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams PCM to a USB DAC through the native engine, bypassing Android's mixer.
 *
 * The chain is:
 *
 *   native WAV/FLAC decoder -> writeAudioData -> lock-free ring buffer
 *     -> isochronous URBs -> USB DAC
 *
 * Samples are not touched between the decoder and the wire, which is what makes
 * this path bit-perfect. That also means there is no equalizer here: see
 * [audioEffects].
 *
 * Decoding uses the same `NativeAudioEngine.openDecoder` sessions as
 * [AudioTrackPlaybackSink], so both outputs share one decode path.
 *
 * Position reporting: the engine exposes no rendered-frame counter, so position
 * is derived from frames handed to the ring buffer, less the buffer's current
 * fill. That is accurate to roughly one buffer (50 ms by default) and never runs
 * backwards.
 */
class UsbPlaybackSink(
    private val engine: NativeAudioEngine,
    private val listener: PlaybackSink.Listener
) : PlaybackSink {

    private val lifecycleLock = Object()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val requestedSeekFrame = AtomicLong(NO_SEEK)

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    override var positionMs: Long = 0L
        private set

    @Volatile
    override var durationMs: Long = 0L
        private set

    @Volatile
    override var currentFormat: AudioFormatInfo? = null
        private set

    /** Always null: a bit-perfect stream cannot carry platform effects. */
    override val audioEffects: AudioEffectsController? = null

    override val outputName: String
        get() = engine.getDeviceName().ifBlank { "USB DAC" }

    override val isBitPerfect: Boolean = true

    override fun play(trackPath: String) {
        stop()

        val playGeneration = generation.incrementAndGet()
        running = true
        paused = false
        positionMs = 0L
        durationMs = 0L
        currentFormat = null
        requestedSeekFrame.set(NO_SEEK)

        val thread = Thread({ runPlayback(trackPath, playGeneration) }, "BitPerfect-USB")
        workerThread = thread
        thread.start()
    }

    override fun pause(): Boolean = synchronized(lifecycleLock) {
        if (!running || paused) return@synchronized false
        paused = true
        engine.pausePlayback()
        true
    }

    override fun resume(): Boolean = synchronized(lifecycleLock) {
        if (!running || !paused) return@synchronized false
        if (!engine.resumePlayback()) return@synchronized false
        paused = false
        lifecycleLock.notifyAll()
        true
    }

    override fun seekTo(positionMs: Long): Boolean = synchronized(lifecycleLock) {
        val format = currentFormat ?: return@synchronized false
        if (!running) return@synchronized false

        val clampedMs = positionMs.coerceIn(0L, durationMs)
        requestedSeekFrame.set(millisecondsToFrames(clampedMs, format.sampleRate))
        this.positionMs = clampedMs
        lifecycleLock.notifyAll()
        true
    }

    override fun overridePosition(positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0L)
    }

    override fun stop() {
        generation.incrementAndGet()
        running = false
        paused = false
        requestedSeekFrame.set(NO_SEEK)

        synchronized(lifecycleLock) {
            lifecycleLock.notifyAll()
        }

        // Stopping the engine also resets the ring buffer, so the worker's next
        // write returns 0 and it exits promptly.
        engine.stopPlayback()

        val thread = workerThread
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(STOP_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (workerThread === thread && thread?.isAlive != true) {
            workerThread = null
        }

        positionMs = 0L
        durationMs = 0L
        currentFormat = null
    }

    override fun release() {
        stop()
    }

    private fun runPlayback(trackPath: String, playGeneration: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var decoder: NativeAudioEngine.DecoderSession? = null
        var completed = false
        var failure: String? = null

        try {
            val openedDecoder = engine.openDecoder(trackPath)
                ?: throw PlaybackException("Unsupported or unreadable file (WAV and FLAC)")
            decoder = openedDecoder

            val decoderFormat = openedDecoder.format
            val bytesPerFrame = decoderFormat.bytesPerFrame
            if (bytesPerFrame <= 0) throw PlaybackException("Invalid PCM frame size")

            val nativeFormat = nativeFormatFor(decoderFormat.bitsPerSample)

            if (!engine.configure(
                    decoderFormat.sampleRate,
                    nativeFormat,
                    decoderFormat.channels,
                    BUFFER_MS
                )
            ) {
                throw PlaybackException(
                    "The DAC did not accept ${decoderFormat.sampleRate} Hz / " +
                        "${decoderFormat.bitsPerSample}-bit"
                )
            }

            if (!engine.startPlayback()) throw PlaybackException("Could not start USB streaming")

            // startPlayback() succeeding does not by itself mean bytes are
            // leaving the device, so this is checked explicitly rather than
            // assumed. Without it a silent failure looks like normal playback.
            if (!engine.isUsbOutputActive()) {
                throw PlaybackException(
                    "No USB audio transport is active (transport: ${engine.getTransportName()})"
                )
            }

            val formatInfo = AudioFormatInfo(
                sampleRate = decoderFormat.sampleRate,
                bitDepth = decoderFormat.bitsPerSample,
                channels = decoderFormat.channels,
                codec = codecName(trackPath)
            )
            currentFormat = formatInfo
            durationMs = decoderFormat.durationMs
            postPrepared(playGeneration, trackPath, formatInfo, durationMs)

            val pcmBuffer = ByteBuffer.allocateDirect(CHUNK_FRAMES * bytesPerFrame)
            val transferBuffer = ByteArray(CHUNK_FRAMES * bytesPerFrame)
            var framesSubmitted = 0L
            var positionBaseFrame = 0L

            while (isCurrent(playGeneration)) {
                waitWhilePaused(playGeneration)
                if (!isCurrent(playGeneration)) break

                val seekFrame = requestedSeekFrame.getAndSet(NO_SEEK)
                if (seekFrame != NO_SEEK) {
                    val actualFrame = openedDecoder.seek(seekFrame)
                    if (actualFrame < 0) throw PlaybackException("Could not seek in this file")

                    // The ring buffer still holds audio from before the seek.
                    // There is no flush, so the stream is restarted to drop it;
                    // otherwise the DAC would play a stale fragment first.
                    engine.stopPlayback()
                    if (!engine.configure(
                            decoderFormat.sampleRate,
                            nativeFormat,
                            decoderFormat.channels,
                            BUFFER_MS
                        ) || !engine.startPlayback()
                    ) {
                        throw PlaybackException("Could not restart the DAC after seeking")
                    }

                    positionBaseFrame = actualFrame
                    framesSubmitted = 0L
                    updatePosition(positionBaseFrame, decoderFormat.sampleRate)
                    continue
                }

                pcmBuffer.clear()
                val framesRead = openedDecoder.read(pcmBuffer, CHUNK_FRAMES)
                if (framesRead < 0) throw PlaybackException("Native decoder read failed")
                if (framesRead == 0) {
                    waitForBufferToDrain(playGeneration, positionBaseFrame + framesSubmitted,
                                         decoderFormat.sampleRate)
                    completed = isCurrent(playGeneration)
                    break
                }

                val bytesRead = framesRead * bytesPerFrame
                pcmBuffer.position(0)
                pcmBuffer.get(transferBuffer, 0, bytesRead)

                var offset = 0
                while (offset < bytesRead && isCurrent(playGeneration)) {
                    if (paused) {
                        waitWhilePaused(playGeneration)
                        continue
                    }
                    if (requestedSeekFrame.get() != NO_SEEK) break

                    val written = engine.writeAudioData(transferBuffer, offset, bytesRead - offset)
                    if (written > 0) {
                        offset += written
                    } else {
                        // Ring buffer full: the DAC is consuming at its own
                        // clock, so this is the normal steady state.
                        Thread.sleep(BACKPRESSURE_SLEEP_MS)
                    }
                }

                framesSubmitted += framesRead
                updateBufferedPosition(positionBaseFrame, framesSubmitted, bytesPerFrame,
                                       decoderFormat.sampleRate)
            }
        } catch (error: PlaybackException) {
            failure = error.message
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: IllegalStateException) {
            if (isCurrent(playGeneration)) {
                failure = "USB output stopped unexpectedly: ${error.message.orEmpty()}"
            }
        } catch (error: UnsatisfiedLinkError) {
            failure = "Native engine is unavailable: ${error.message.orEmpty()}"
        } finally {
            decoder?.close()
            if (workerThread === Thread.currentThread()) workerThread = null
            if (generation.get() == playGeneration) running = false
        }

        if (completed) {
            postCompleted(playGeneration, trackPath)
        } else if (failure != null && isCurrentGeneration(playGeneration)) {
            postError(playGeneration, trackPath, failure)
        }
    }

    /**
     * Wait for the DAC to consume what has already been queued, so the tail of
     * a track is not cut off when the decoder reaches EOF.
     */
    private fun waitForBufferToDrain(playGeneration: Long, endFrame: Long, sampleRate: Int) {
        var lastLevel = Float.MAX_VALUE
        var stalledPolls = 0

        while (isCurrent(playGeneration)) {
            if (paused) {
                waitWhilePaused(playGeneration)
                continue
            }

            val level = engine.getBufferLevel()
            if (level <= BUFFER_EMPTY_THRESHOLD) {
                updatePosition(endFrame, sampleRate)
                return
            }

            // If the level stops falling the DAC has gone away; give up rather
            // than hanging on a track that will never finish.
            if (level >= lastLevel) {
                if (++stalledPolls >= DRAIN_STALL_POLLS) return
            } else {
                stalledPolls = 0
            }
            lastLevel = level

            try {
                Thread.sleep(DRAIN_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun waitWhilePaused(playGeneration: Long) {
        synchronized(lifecycleLock) {
            while (paused && isCurrent(playGeneration) &&
                requestedSeekFrame.get() == NO_SEEK
            ) {
                try {
                    lifecycleLock.wait(PAUSE_WAIT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    running = false
                    return
                }
            }
        }
    }

    /**
     * Report where the DAC has reached, not where the decoder has reached, by
     * discounting whatever is still sitting in the ring buffer.
     */
    private fun updateBufferedPosition(
        positionBaseFrame: Long,
        framesSubmitted: Long,
        bytesPerFrame: Int,
        sampleRate: Int
    ) {
        val bufferedBytes = (engine.getBufferLevel() * bufferBytes(sampleRate, bytesPerFrame))
            .toLong()
            .coerceAtLeast(0L)
        val bufferedFrames = if (bytesPerFrame > 0) bufferedBytes / bytesPerFrame else 0L
        val rendered = (framesSubmitted - bufferedFrames).coerceAtLeast(0L)
        updatePosition(positionBaseFrame + rendered, sampleRate)
    }

    private fun bufferBytes(sampleRate: Int, bytesPerFrame: Int): Float =
        (sampleRate.toLong() * bytesPerFrame * BUFFER_MS / 1000L).toFloat()

    private fun updatePosition(frame: Long, sampleRate: Int) {
        if (sampleRate <= 0) return
        positionMs = framesToMilliseconds(frame, sampleRate).coerceIn(0L, durationMs)
    }

    private fun postPrepared(
        playGeneration: Long,
        trackPath: String,
        format: AudioFormatInfo,
        durationMs: Long
    ) {
        mainHandler.post {
            if (isCurrentGeneration(playGeneration)) {
                listener.onPrepared(trackPath, format, durationMs)
            }
        }
    }

    private fun postCompleted(playGeneration: Long, trackPath: String) {
        mainHandler.post {
            if (isCurrentGeneration(playGeneration)) listener.onCompleted(trackPath)
        }
    }

    private fun postError(playGeneration: Long, trackPath: String, message: String) {
        mainHandler.post {
            if (isCurrentGeneration(playGeneration)) listener.onError(trackPath, message)
        }
    }

    private fun isCurrent(playGeneration: Long): Boolean =
        running && isCurrentGeneration(playGeneration)

    private fun isCurrentGeneration(playGeneration: Long): Boolean =
        generation.get() == playGeneration

    private fun nativeFormatFor(bitsPerSample: Int): Int = when (bitsPerSample) {
        16 -> NativeAudioEngine.FORMAT_S16_LE
        // The native WAV and FLAC decoders emit 24-bit as three packed bytes,
        // not padded into four.
        24 -> NativeAudioEngine.FORMAT_S24_3LE
        32 -> NativeAudioEngine.FORMAT_S32_LE
        else -> throw PlaybackException("Unsupported PCM bit depth: $bitsPerSample")
    }

    private fun codecName(path: String): String = when (
        path.substringAfterLast('.', "").lowercase()
    ) {
        "flac" -> "FLAC"
        "wav", "wave" -> "WAV"
        else -> "PCM"
    }

    private fun framesToMilliseconds(frames: Long, sampleRate: Int): Long =
        (frames / sampleRate) * 1000L + (frames % sampleRate) * 1000L / sampleRate

    private fun millisecondsToFrames(milliseconds: Long, sampleRate: Int): Long =
        (milliseconds / 1000L) * sampleRate + (milliseconds % 1000L) * sampleRate / 1000L

    private class PlaybackException(message: String) : Exception(message)

    private companion object {
        const val CHUNK_FRAMES = 2048
        const val BUFFER_MS = 50
        const val NO_SEEK = -1L
        const val PAUSE_WAIT_MS = 100L
        const val BACKPRESSURE_SLEEP_MS = 2L
        const val DRAIN_POLL_MS = 5L
        const val DRAIN_STALL_POLLS = 200
        const val BUFFER_EMPTY_THRESHOLD = 0.02f
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
