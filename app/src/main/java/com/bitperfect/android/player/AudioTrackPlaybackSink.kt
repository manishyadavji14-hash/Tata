package com.bitperfect.android.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.bitperfect.android.engine.NativeAudioEngine
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Streams PCM produced by the native WAV/FLAC decoders to Android AudioTrack.
 *
 * This is intentionally an Android PCM validation path. Android may still mix
 * or resample it; USB bit-perfect transport remains a separate output backend.
 */
class AudioTrackPlaybackSink(
    private val engine: NativeAudioEngine,
    private val listener: PlaybackSink.Listener,
    /**
     * Equalizer and bass boost for this output path only.
     *
     * Platform effects bind to an AudioTrack session, so they cannot reach a
     * bit-perfect USB stream that bypasses AudioTrack.
     */
    override val audioEffects: AudioEffectsController = AudioEffectsController()
) : PlaybackSink {

    override val outputName: String = "Android output"

    /**
     * False: Android may resample or mix this stream on its way out. Use the USB
     * sink for an unmodified signal path.
     */
    override val isBitPerfect: Boolean = false

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
    private var audioTrack: AudioTrack? = null

    @Volatile
    override var positionMs: Long = 0L
        private set

    @Volatile
    override var durationMs: Long = 0L
        private set

    @Volatile
    override var currentFormat: AudioFormatInfo? = null
        private set

    override fun play(trackPath: String) {
        stop()

        val playGeneration = generation.incrementAndGet()
        running = true
        paused = false
        positionMs = 0L
        durationMs = 0L
        currentFormat = null
        requestedSeekFrame.set(NO_SEEK)

        val thread = Thread(
            { runPlayback(trackPath, playGeneration) },
            "BitPerfect-AudioTrack"
        )
        workerThread = thread
        thread.start()
    }

    override fun pause(): Boolean = synchronized(lifecycleLock) {
        if (!running || paused) return@synchronized false
        val track = audioTrack ?: return@synchronized false
        try {
            track.pause()
            paused = true
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun resume(): Boolean = synchronized(lifecycleLock) {
        if (!running || !paused) return@synchronized false
        val track = audioTrack ?: return@synchronized false
        try {
            track.play()
            paused = false
            lifecycleLock.notifyAll()
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun seekTo(positionMs: Long): Boolean = synchronized(lifecycleLock) {
        val format = currentFormat ?: return@synchronized false
        if (!running) return@synchronized false
        val track = audioTrack ?: return@synchronized false

        val clampedMs = positionMs.coerceIn(0L, durationMs)
        val frame = millisecondsToFrames(clampedMs, format.sampleRate)
        val wasPaused = paused

        try {
            track.pause()
            track.flush()
        } catch (_: IllegalStateException) {
            if (!wasPaused) {
                try {
                    track.play()
                } catch (_: IllegalStateException) {
                    // The original command failed; preserve the rejected result.
                }
            }
            return@synchronized false
        }

        requestedSeekFrame.set(frame)
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
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.stop()
            } catch (_: IllegalStateException) {
                // The worker may still be constructing or releasing the track.
            }
            lifecycleLock.notifyAll()
        }

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
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        var decoder: NativeAudioEngine.DecoderSession? = null
        var track: AudioTrack? = null
        var effectsSessionId: Int? = null
        var completed = false
        var drained = false
        var failure: String? = null

        try {
            val openedDecoder = engine.openDecoder(trackPath)
                ?: throw PlaybackException("Unsupported or unreadable file (WAV and FLAC are supported)")
            decoder = openedDecoder

            val decoderFormat = openedDecoder.format
            validateFormat(decoderFormat)
            val encoding = audioEncoding(decoderFormat.bitsPerSample)
            val channelMask = channelMask(decoderFormat.channels)
            val bytesPerFrame = decoderFormat.bytesPerFrame
            if (bytesPerFrame <= 0) throw PlaybackException("Invalid PCM frame size")

            val minBufferSize = AudioTrack.getMinBufferSize(
                decoderFormat.sampleRate,
                channelMask,
                encoding
            )
            if (minBufferSize <= 0) {
                throw PlaybackException(
                    "AudioTrack does not support ${decoderFormat.sampleRate} Hz / " +
                        "${decoderFormat.bitsPerSample}-bit / ${decoderFormat.channels}ch"
                )
            }

            val chunkBytes = CHUNK_FRAMES * bytesPerFrame
            val streamBufferBytes = alignToFrame(
                max(minBufferSize, chunkBytes * AUDIO_TRACK_BUFFER_CHUNKS),
                bytesPerFrame
            )

            val openedTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(decoderFormat.sampleRate)
                        .setEncoding(encoding)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(streamBufferBytes)
                .build()
            track = openedTrack

            if (openedTrack.state != AudioTrack.STATE_INITIALIZED) {
                throw PlaybackException("AudioTrack failed to initialize")
            }
            if (!isCurrent(playGeneration)) return

            synchronized(lifecycleLock) {
                audioTrack = openedTrack
            }

            // Each track owns a fresh session, so effects are rebuilt here and
            // the retained user curve is re-applied.
            effectsSessionId = openedTrack.audioSessionId
            audioEffects.attach(openedTrack.audioSessionId)

            val formatInfo = AudioFormatInfo(
                sampleRate = decoderFormat.sampleRate,
                bitDepth = decoderFormat.bitsPerSample,
                channels = decoderFormat.channels,
                codec = codecName(trackPath)
            )
            currentFormat = formatInfo
            durationMs = decoderFormat.durationMs
            postPrepared(playGeneration, trackPath, formatInfo, durationMs)

            val pcmBuffer = ByteBuffer.allocateDirect(chunkBytes)
            var positionBaseFrame = 0L
            var playbackHeadAnchor = unsignedPlaybackHead(openedTrack)
            var submittedFramesSinceAnchor = 0L

            openedTrack.play()

            while (isCurrent(playGeneration)) {
                waitWhilePaused(playGeneration)
                if (!isCurrent(playGeneration)) break

                val seekFrame = requestedSeekFrame.getAndSet(NO_SEEK)
                if (seekFrame != NO_SEEK) {
                    val shouldResume = !paused
                    openedTrack.pause()
                    openedTrack.flush()
                    val actualFrame = openedDecoder.seek(seekFrame)
                    if (actualFrame < 0) throw PlaybackException("Could not seek in this file")
                    positionBaseFrame = actualFrame
                    playbackHeadAnchor = unsignedPlaybackHead(openedTrack)
                    submittedFramesSinceAnchor = 0L
                    pcmBuffer.clear()
                    updatePosition(positionBaseFrame, decoderFormat.sampleRate)
                    if (shouldResume && isCurrent(playGeneration)) openedTrack.play()
                    continue
                }

                pcmBuffer.clear()
                val framesRead = openedDecoder.read(pcmBuffer, CHUNK_FRAMES)
                if (framesRead < 0) throw PlaybackException("Native decoder read failed")
                if (framesRead == 0) {
                    drainAndAwaitCompletion(
                        openedTrack,
                        playGeneration,
                        playbackHeadAnchor,
                        submittedFramesSinceAnchor,
                        positionBaseFrame,
                        decoderFormat.sampleRate
                    )
                    drained = true
                    completed = isCurrent(playGeneration)
                    break
                }

                val bytesRead = framesRead * bytesPerFrame
                pcmBuffer.position(0)
                pcmBuffer.limit(bytesRead)

                while (pcmBuffer.hasRemaining() && isCurrent(playGeneration)) {
                    if (paused) {
                        waitWhilePaused(playGeneration)
                        continue
                    }
                    if (requestedSeekFrame.get() != NO_SEEK) break
                    val bytesWritten = openedTrack.write(
                        pcmBuffer,
                        pcmBuffer.remaining(),
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (bytesWritten < 0) {
                        throw PlaybackException("AudioTrack write failed ($bytesWritten)")
                    }
                    if (bytesWritten == 0) continue
                    if (bytesWritten % bytesPerFrame != 0) {
                        throw PlaybackException("AudioTrack wrote a partial PCM frame")
                    }
                    submittedFramesSinceAnchor += bytesWritten / bytesPerFrame
                    updateRenderedPosition(
                        openedTrack,
                        playbackHeadAnchor,
                        submittedFramesSinceAnchor,
                        positionBaseFrame,
                        decoderFormat.sampleRate
                    )
                }
            }
        } catch (error: PlaybackException) {
            failure = error.message
        } catch (error: SecurityException) {
            failure = "Audio output permission denied: ${error.message.orEmpty()}"
        } catch (error: IllegalArgumentException) {
            failure = "Unsupported audio output format: ${error.message.orEmpty()}"
        } catch (error: IllegalStateException) {
            if (isCurrent(playGeneration)) {
                failure = "Audio output stopped unexpectedly: ${error.message.orEmpty()}"
            }
        } catch (error: UnsatisfiedLinkError) {
            failure = "Native decoder is unavailable: ${error.message.orEmpty()}"
        } finally {
            synchronized(lifecycleLock) {
                if (audioTrack === track) audioTrack = null
            }
            // Release before the track itself, and only if this worker still
            // owns the effects: a late-exiting worker must not tear down the
            // effects a newly started track has already attached.
            effectsSessionId?.let { audioEffects.detach(it) }
            try {
                // Only discard buffered audio when the track did NOT run to the
                // end. After a clean finish the tail has already been drained by
                // drainAndAwaitCompletion, and flushing here would throw away
                // the last fraction of a second of every track.
                if (!drained) {
                    track?.pause()
                    track?.flush()
                    track?.stop()
                }
            } catch (_: IllegalStateException) {
                // Already stopped or never entered the playing state.
            }
            track?.release()
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

    private fun waitWhilePaused(playGeneration: Long) {
        synchronized(lifecycleLock) {
            while (paused && isCurrent(playGeneration) && requestedSeekFrame.get() == NO_SEEK) {
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
     * Play out whatever is still buffered at the end of a track, then return.
     *
     * `stop()` is what does the work. In MODE_STREAM, AudioTrack will not render
     * a trailing partial buffer while it is still PLAYING — it sits waiting for
     * the buffer to be filled, so `playbackHeadPosition` stops short of what was
     * written and never catches up. `stop()` is the documented way to make it
     * play out what it already holds. Polling the head without calling it was
     * the cause of "Audio output stalled while draining the final buffer" at the
     * end of every track.
     *
     * This never throws. Not being able to observe the drain is not a playback
     * failure: the audio has already been handed over, and the track is over
     * either way. Reporting an error here turned a normal track ending into a
     * failure the user could see.
     */
    private fun drainAndAwaitCompletion(
        track: AudioTrack,
        playGeneration: Long,
        playbackHeadAnchor: Long,
        submittedFrames: Long,
        positionBaseFrame: Long,
        sampleRate: Int
    ) {
        // A paused track has to be resumed or stop() has nothing to drain into.
        waitWhilePaused(playGeneration)
        if (!isCurrent(playGeneration)) return

        try {
            track.stop()
        } catch (_: IllegalStateException) {
            return
        }

        // Bound the wait by how much audio can still be outstanding, plus a
        // margin, rather than by a fixed timeout. At most one AudioTrack buffer
        // is left, so this is tens of milliseconds in practice.
        val outstandingFrames = (submittedFrames -
            renderedFrames(track, playbackHeadAnchor, submittedFrames)).coerceAtLeast(0L)
        val deadline = SystemClock.elapsedRealtime() +
            framesToMilliseconds(outstandingFrames, sampleRate) + DRAIN_MARGIN_MS

        while (isCurrent(playGeneration) && SystemClock.elapsedRealtime() < deadline) {
            val rendered = renderedFrames(track, playbackHeadAnchor, submittedFrames)
            updatePosition(positionBaseFrame + rendered, sampleRate)

            // PLAYSTATE_STOPPED is only reached once the buffered audio has been
            // played out, so it is the real signal that the tail is done.
            if (track.playState == AudioTrack.PLAYSTATE_STOPPED &&
                rendered >= submittedFrames
            ) {
                break
            }

            try {
                Thread.sleep(DRAIN_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        // Report the track as finished regardless of what the head reported, so
        // the progress bar lands on the end instead of a fraction short.
        updatePosition(positionBaseFrame + submittedFrames, sampleRate)
    }

    private fun updateRenderedPosition(
        track: AudioTrack,
        playbackHeadAnchor: Long,
        submittedFrames: Long,
        positionBaseFrame: Long,
        sampleRate: Int
    ) {
        val rendered = renderedFrames(track, playbackHeadAnchor, submittedFrames)
        updatePosition(positionBaseFrame + rendered, sampleRate)
    }

    private fun renderedFrames(
        track: AudioTrack,
        playbackHeadAnchor: Long,
        submittedFrames: Long
    ): Long {
        val currentHead = unsignedPlaybackHead(track)
        val delta = (currentHead - playbackHeadAnchor) and UNSIGNED_INT_MASK
        // Some devices reset playbackHeadPosition after flush, which makes the
        // delta meaningless. Clamp rather than collapsing to 0: returning 0 made
        // the reported position jump backwards to the start of the track, and
        // made the drain check unsatisfiable so it could never see the end.
        return delta.coerceIn(0L, submittedFrames)
    }

    private fun updatePosition(frame: Long, sampleRate: Int) {
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

    private fun validateFormat(format: NativeAudioEngine.DecoderFormat) {
        if (format.sampleRate <= 0 || format.channels !in 1..2) {
            throw PlaybackException("Only mono and stereo PCM files are supported")
        }
        if (format.bitsPerSample !in SUPPORTED_BIT_DEPTHS) {
            throw PlaybackException("Only 16-, 24-, and 32-bit integer PCM is supported")
        }
        if (format.totalFrames <= 0L) {
            throw PlaybackException("The file does not report a valid duration")
        }
        if (format.bitsPerSample > 16 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw PlaybackException("24/32-bit AudioTrack output requires Android 12 or newer")
        }
    }

    private fun audioEncoding(bitsPerSample: Int): Int = when (bitsPerSample) {
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        32 -> AudioFormat.ENCODING_PCM_32BIT
        else -> throw PlaybackException("Unsupported PCM bit depth: $bitsPerSample")
    }

    private fun channelMask(channels: Int): Int = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> throw PlaybackException("Unsupported channel count: $channels")
    }

    private fun codecName(path: String): String = when (
        path.substringAfterLast('.', "").lowercase()
    ) {
        "flac" -> "FLAC"
        "wav", "wave" -> "WAV"
        else -> "PCM"
    }

    private fun alignToFrame(bytes: Int, bytesPerFrame: Int): Int {
        val remainder = bytes % bytesPerFrame
        return if (remainder == 0) bytes else bytes + bytesPerFrame - remainder
    }

    private fun unsignedPlaybackHead(track: AudioTrack): Long =
        track.playbackHeadPosition.toLong() and UNSIGNED_INT_MASK

    private fun framesToMilliseconds(frames: Long, sampleRate: Int): Long =
        (frames / sampleRate) * 1000L + (frames % sampleRate) * 1000L / sampleRate

    private fun millisecondsToFrames(milliseconds: Long, sampleRate: Int): Long =
        (milliseconds / 1000L) * sampleRate +
            (milliseconds % 1000L) * sampleRate / 1000L

    private class PlaybackException(message: String) : Exception(message)

    private companion object {
        const val CHUNK_FRAMES = 2048
        const val AUDIO_TRACK_BUFFER_CHUNKS = 4
        const val NO_SEEK = -1L
        const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL
        const val PAUSE_WAIT_MS = 100L
        const val DRAIN_POLL_MS = 5L

        /**
         * Slack on top of the outstanding audio when waiting for the tail.
         * Covers scheduling jitter and the mixer's own latency.
         */
        const val DRAIN_MARGIN_MS = 400L
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
        val SUPPORTED_BIT_DEPTHS = setOf(16, 24, 32)
    }
}
