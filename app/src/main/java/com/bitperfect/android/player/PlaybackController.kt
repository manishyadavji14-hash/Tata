package com.bitperfect.android.player

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
 * - Manages shuffle and repeat modes via PlayQueue
 * - Notifies listeners of state changes
 * - Handles track transitions (gapless when possible)
 */
class PlaybackController(
    private val engine: NativeAudioEngine
) {
    private var _state: PlaybackState = PlaybackState.Idle
    val state: PlaybackState get() = _state

    val queue: PlayQueue = PlayQueue()
    private var sleepTimer: SleepTimer? = null
    private val stateListeners = mutableListOf<(PlaybackState) -> Unit>()
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L

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
        // Native engine handles the actual seek
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
     * Called when a track transition occurs (from native gapless engine).
     * Advances the queue and updates state.
     */
    fun onTrackTransition() {
        val nextTrack = queue.next()
        if (nextTrack != null) {
            positionMs = 0L
            val format = AudioFormatInfo(
                sampleRate = engine.getCurrentSampleRate(),
                bitDepth = engine.getCurrentBitDepth(),
                channels = engine.getCurrentChannels()
            )
            setState(PlaybackState.Playing(
                trackPath = nextTrack,
                positionMs = 0L,
                durationMs = durationMs,
                format = format
            ))
        } else {
            stop()
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
        stop()
        cancelSleepTimer()
        stateListeners.clear()
    }

    private fun resume() {
        val currentState = _state
        if (currentState is PlaybackState.Paused) {
            engine.resumePlayback()
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
        setState(PlaybackState.Loading(trackPath))
        positionMs = 0L

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
}
