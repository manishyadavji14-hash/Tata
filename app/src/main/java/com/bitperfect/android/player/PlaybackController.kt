package com.bitperfect.android.player

import android.util.Log
import com.bitperfect.android.engine.NativeAudioEngine

/**
 * PlaybackController - Player state machine managing playback lifecycle.
 *
 * States: Idle -> Loading -> Playing <-> Paused -> Stopped
 *                                    -> Error
 *
 * Responsibilities:
 * - Manages play/pause/stop/seek/next/previous operations
 * - Coordinates with NativeAudioEngine for audio output
 * - Manages the AudioDecodeThread for feeding PCM data to the engine
 * - Manages shuffle and repeat modes via PlayQueue
 * - Notifies listeners of state changes
 * - Handles track transitions (gapless when possible)
 */
class PlaybackController(
    private val engine: NativeAudioEngine
) {
    companion object {
        private const val TAG = "PlaybackController"
    }

    private var _state: PlaybackState = PlaybackState.Idle
    val state: PlaybackState get() = _state

    val queue: PlayQueue = PlayQueue()
    private var sleepTimer: SleepTimer? = null
    private val stateListeners = mutableListOf<(PlaybackState) -> Unit>()
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L

    // Audio decode thread - feeds PCM data to the native engine
    private var decodeThread: AudioDecodeThread? = null

    /**
     * Add a state change listener.
     */
    fun addStateListener(listener: (PlaybackState) -> Unit) {
        stateListeners.add(listener)
    }

    /**
     * Remove a state change listener.
     */
    fun removeStateListener(listener: (PlaybackState) -> Unit) {
        stateListeners.remove(listener)
    }

    /**
     * Play the current track in the queue.
     * If paused, resumes. If stopped/idle, starts from current queue position.
     */
    fun play() {
        when (_state) {
            is PlaybackState.Paused -> resume()
            is PlaybackState.Idle, is PlaybackState.Stopped -> {
                val track = queue.currentTrack ?: return
                startTrack(track)
            }
            is PlaybackState.Playing -> { /* Already playing */ }
            else -> { /* Cannot play from Loading or Error state */ }
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        val currentState = _state
        if (currentState is PlaybackState.Playing) {
            decodeThread?.pauseDecoding()
            engine.pausePlayback()
            setState(PlaybackState.Paused(
                trackPath = currentState.trackPath,
                positionMs = positionMs
            ))
        }
    }

    /**
     * Stop playback completely.
     */
    fun stop() {
        stopDecodeThread()
        engine.stopPlayback()
        positionMs = 0L
        setState(PlaybackState.Stopped)
    }

    /**
     * Seek to a position in the current track.
     * @param positionMs Target position in milliseconds
     */
    fun seek(positionMs: Long) {
        this.positionMs = positionMs
        // Tell the decode thread to seek
        decodeThread?.seekTo(positionMs)
        // State remains the same (playing or paused)
        val currentState = _state
        when (currentState) {
            is PlaybackState.Playing -> {
                setState(PlaybackState.Playing(
                    trackPath = currentState.trackPath,
                    positionMs = positionMs,
                    durationMs = currentState.durationMs,
                    format = currentState.format
                ))
            }
            is PlaybackState.Paused -> {
                setState(PlaybackState.Paused(
                    trackPath = currentState.trackPath,
                    positionMs = positionMs
                ))
            }
            else -> { }
        }
    }

    /**
     * Skip to the next track.
     */
    fun next() {
        val nextTrack = queue.next() ?: run {
            stop()
            return
        }
        startTrack(nextTrack)
    }

    /**
     * Go to the previous track.
     * If position > 3 seconds, restart current track instead.
     */
    fun previous() {
        if (positionMs > 3000) {
            // Restart current track
            seek(0)
            return
        }
        val prevTrack = queue.previous() ?: return
        startTrack(prevTrack)
    }

    /**
     * Set the repeat mode.
     */
    fun setRepeatMode(mode: RepeatMode) {
        queue.repeatMode = mode
    }

    /**
     * Get the current repeat mode.
     */
    fun getRepeatMode(): RepeatMode = queue.repeatMode

    /**
     * Toggle shuffle on/off.
     */
    fun toggleShuffle() {
        queue.setShuffle(!queue.isShuffleEnabled())
    }

    /**
     * Set shuffle state explicitly.
     */
    fun setShuffle(enabled: Boolean) {
        queue.setShuffle(enabled)
    }

    /**
     * Check if shuffle is enabled.
     */
    fun isShuffleEnabled(): Boolean = queue.isShuffleEnabled()

    /**
     * Set a sleep timer.
     * @param durationMs Duration in milliseconds before pausing
     */
    fun setSleepTimer(durationMs: Long) {
        sleepTimer = SleepTimer(durationMs) {
            pause()
        }
        sleepTimer?.start()
    }

    /**
     * Cancel the sleep timer.
     */
    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
    }

    /**
     * Get remaining sleep timer time in milliseconds.
     * @return Remaining time, or null if no timer is active
     */
    fun getSleepTimerRemaining(): Long? = sleepTimer?.remainingMs

    /**
     * Play a track from a list context (e.g., library track click).
     * Replaces the current queue with the given track list, sets the start position,
     * stops any current playback, and begins playing the selected track.
     *
     * @param trackPaths The full list of track paths to populate the queue with
     * @param startIndex The index of the track to begin playback from
     */
    fun playTrackFromList(trackPaths: List<String>, startIndex: Int) {
        queue.setQueue(trackPaths, startIndex)
        stop()
        play()
    }

    /**
     * Jump to a specific track in the current queue by index.
     * Stops current playback and starts the track at the given index.
     *
     * @param index The queue index to jump to
     */
    fun jumpToQueueIndex(index: Int) {
        val track = queue.jumpTo(index) ?: return
        stopDecodeThread()
        startTrack(track)
    }

    /**
     * Remove a track from the queue at the given index.
     * If the removed track is the currently playing track, advance to next.
     *
     * @param index The queue index to remove
     */
    fun removeFromQueue(index: Int) {
        val wasCurrentIndex = queue.position
        val removed = queue.removeAt(index)
        if (!removed) return

        if (index == wasCurrentIndex) {
            // The currently playing track was removed, play next or stop
            val nextTrack = queue.currentTrack
            if (nextTrack != null) {
                stopDecodeThread()
                startTrack(nextTrack)
            } else {
                stop()
            }
        }
    }

    /**
     * Move a track in the queue from one position to another.
     *
     * @param fromIndex Source index
     * @param toIndex Destination index
     */
    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        queue.move(fromIndex, toIndex)
    }

    /**
     * Called when a track transition occurs (from native gapless engine or decode thread).
     * Stops the current decode thread and starts the next track.
     */
    fun onTrackTransition() {
        // Stop the current decode thread (it has finished or will be replaced)
        stopDecodeThread()

        val nextTrack = queue.next()
        if (nextTrack != null) {
            startTrack(nextTrack)
        } else {
            // No more tracks in queue
            engine.stopPlayback()
            positionMs = 0L
            durationMs = 0L
            setState(PlaybackState.Stopped)
        }
    }

    /**
     * Update the current playback position (called periodically from a timer).
     */
    fun updatePosition(newPositionMs: Long) {
        positionMs = newPositionMs
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopDecodeThread()
        stop()
        cancelSleepTimer()
        stateListeners.clear()
    }

    private fun resume() {
        val currentState = _state
        if (currentState is PlaybackState.Paused) {
            engine.resumePlayback()
            decodeThread?.resumeDecoding()
            val format = AudioFormatInfo(
                sampleRate = engine.getCurrentSampleRate(),
                bitDepth = engine.getCurrentBitDepth(),
                channels = engine.getCurrentChannels()
            )
            setState(PlaybackState.Playing(
                trackPath = currentState.trackPath,
                positionMs = currentState.positionMs,
                durationMs = durationMs,
                format = format
            ))
        }
    }

    private fun startTrack(trackPath: String) {
        // Stop any existing decode thread before starting a new one
        stopDecodeThread()

        setState(PlaybackState.Loading(trackPath))
        positionMs = 0L
        durationMs = 0L

        // Query the native engine for the actual format of the file.
        // The engine opens the appropriate decoder (WAV/FLAC/DSF),
        // detects sample rate, bit depth, and channels, then runs
        // mode selection (PCM/DoP/Native DSD) based on DAC capabilities.

        // Determine format from file extension/content via native engine
        val detectedFormat = engine.detectFileFormat(trackPath)
        val sampleRate = if (detectedFormat.sampleRate > 0) detectedFormat.sampleRate else 44100
        val format = detectedFormat.nativeFormat
        val channels = if (detectedFormat.channels > 0) detectedFormat.channels else 2

        val configured = engine.configure(
            sampleRate,
            format,
            channels,
            50  // 50ms buffer
        )

        if (!configured) {
            setState(PlaybackState.Error("Failed to configure audio engine", trackPath))
            return
        }

        if (!engine.startPlayback()) {
            setState(PlaybackState.Error("Failed to start playback", trackPath))
            return
        }

        val bitDepth = when (format) {
            NativeAudioEngine.FORMAT_S16_LE -> 16
            NativeAudioEngine.FORMAT_S24_3LE, NativeAudioEngine.FORMAT_S24_LE -> 24
            NativeAudioEngine.FORMAT_S32_LE -> 32
            else -> 16
        }

        // Create and start the decode thread to feed PCM data to the engine
        decodeThread = AudioDecodeThread(
            engine = engine,
            trackPath = trackPath,
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
            onPositionUpdate = { newPositionMs ->
                positionMs = newPositionMs
                // Update the Playing state so UI reflects current position
                val currentState = _state
                if (currentState is PlaybackState.Playing) {
                    setState(PlaybackState.Playing(
                        trackPath = currentState.trackPath,
                        positionMs = newPositionMs,
                        durationMs = durationMs,
                        format = currentState.format
                    ))
                }
            },
            onDurationDetected = { detectedDurationMs ->
                durationMs = detectedDurationMs
                // Re-emit Playing state with the correct duration
                val currentState = _state
                if (currentState is PlaybackState.Playing) {
                    setState(PlaybackState.Playing(
                        trackPath = currentState.trackPath,
                        positionMs = currentState.positionMs,
                        durationMs = detectedDurationMs,
                        format = currentState.format
                    ))
                }
            },
            onTrackComplete = {
                Log.d(TAG, "Track complete: $trackPath")
                onTrackTransition()
            },
            onError = { errorMessage ->
                Log.e(TAG, "Decode error: $errorMessage")
                setState(PlaybackState.Error(errorMessage, trackPath))
            }
        ).also { it.start() }

        val formatInfo = AudioFormatInfo(
            sampleRate = engine.getCurrentSampleRate(),
            bitDepth = bitDepth,
            channels = channels
        )
        setState(PlaybackState.Playing(
            trackPath = trackPath,
            positionMs = 0L,
            durationMs = durationMs,
            format = formatInfo
        ))
    }

    private fun setState(newState: PlaybackState) {
        _state = newState
        stateListeners.forEach { it(newState) }
    }

    /**
     * Stop and clean up the current decode thread.
     */
    private fun stopDecodeThread() {
        decodeThread?.let { thread ->
            thread.stopDecoding()
            try {
                thread.join(1000) // Wait up to 1 second for graceful shutdown
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for decode thread to stop")
            }
        }
        decodeThread = null
    }
}
