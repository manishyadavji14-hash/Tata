package com.bitperfect.android.player

import com.bitperfect.android.engine.NativeAudioEngine
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Player state machine for the Android PCM validation path.
 *
 * WAV/FLAC files are decoded by the native engine and streamed to AudioTrack.
 * USB bit-perfect transport remains a separate output backend.
 */
class PlaybackController(
    private val engine: NativeAudioEngine
) {
    @Volatile
    private var _state: PlaybackState = PlaybackState.Idle

    val state: PlaybackState
        get() {
            val snapshot = _state
            return when (snapshot) {
                is PlaybackState.Playing -> snapshot.copy(positionMs = playbackSink.positionMs)
                is PlaybackState.Paused -> snapshot.copy(positionMs = playbackSink.positionMs)
                else -> snapshot
            }
        }

    val queue: PlayQueue = PlayQueue()

    /**
     * Equalizer and bass boost for the Android output path.
     *
     * Intentionally reachable only through this sink: the effects bind to an
     * AudioTrack session and so cannot alter bit-perfect USB output.
     */
    val audioEffects: AudioEffectsController get() = playbackSink.audioEffects
    private var sleepTimer: SleepTimer? = null
    private val stateListeners = CopyOnWriteArrayList<(PlaybackState) -> Unit>()

    @Volatile
    private var durationMs: Long = 0L

    @Volatile
    private var currentFormat: AudioFormatInfo? = null

    private val playbackSink = AudioTrackPlaybackSink(
        engine = engine,
        listener = object : AudioTrackPlaybackSink.Listener {
            override fun onPrepared(
                trackPath: String,
                format: AudioFormatInfo,
                durationMs: Long
            ) {
                val loading = _state as? PlaybackState.Loading
                if (loading?.trackPath != trackPath) return

                this@PlaybackController.durationMs = durationMs
                currentFormat = format
                setState(
                    PlaybackState.Playing(
                        trackPath = trackPath,
                        positionMs = 0L,
                        durationMs = durationMs,
                        format = format
                    )
                )
            }

            override fun onCompleted(trackPath: String) {
                val currentPath = when (val current = _state) {
                    is PlaybackState.Playing -> current.trackPath
                    is PlaybackState.Paused -> current.trackPath
                    else -> null
                }
                if (currentPath != trackPath) return

                val nextTrack = queue.next()
                if (nextTrack == null) stop() else startTrack(nextTrack)
            }

            override fun onError(trackPath: String, message: String) {
                setState(PlaybackState.Error(message, trackPath))
            }
        }
    )

    fun addStateListener(listener: (PlaybackState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (PlaybackState) -> Unit) {
        stateListeners.remove(listener)
    }

    /** Replace the queue with one local file and begin playback. */
    fun playFile(trackPath: String) {
        if (trackPath.isBlank()) return
        queue.setQueue(listOf(trackPath))
        startTrack(trackPath)
    }

    /**
     * Replace the queue with a list of tracks and start at one of them.
     *
     * @param startIndex Index within [trackPaths] to begin from.
     */
    fun playQueue(trackPaths: List<String>, startIndex: Int = 0) {
        val playable = trackPaths.filter { it.isNotBlank() }
        if (playable.isEmpty()) return

        val safeIndex = startIndex.coerceIn(0, playable.lastIndex)
        queue.setQueue(playable, safeIndex)
        queue.currentTrack?.let(::startTrack)
    }

    /**
     * Insert a track directly after the one playing.
     *
     * Starts playback when nothing is queued, so the action is never silent.
     */
    fun playNext(trackPath: String) {
        if (trackPath.isBlank()) return

        // Returns false only when the queue is empty, in which case there is
        // nothing to queue behind and the track just starts.
        if (!queue.insertAfterCurrent(trackPath)) {
            playFile(trackPath)
        }
    }

    /**
     * Append a track to the end of the queue.
     */
    fun addToQueue(trackPath: String) {
        if (trackPath.isBlank()) return

        if (queue.isEmpty) {
            playFile(trackPath)
            return
        }
        queue.add(trackPath)
    }

    /**
     * Append several tracks to the end of the queue.
     */
    fun addAllToQueue(trackPaths: List<String>) {
        val playable = trackPaths.filter { it.isNotBlank() }
        if (playable.isEmpty()) return

        if (queue.isEmpty) {
            playQueue(playable)
            return
        }
        queue.addAll(playable)
    }

    /**
     * Jump to a queue entry and play it.
     */
    fun playQueueIndex(index: Int) {
        val track = queue.jumpTo(index) ?: return
        startTrack(track)
    }

    /**
     * Remove a queue entry.
     *
     * Removing the entry being played advances to whatever now occupies that
     * position, so playback does not continue on a track no longer queued.
     */
    fun removeFromQueue(index: Int) {
        // One locked operation, so a track finishing on the audio worker cannot
        // change the current position between the removal and the decision
        // about what to play next.
        val outcome = queue.removeAtTrackingCurrent(index)
        if (!outcome.removed || !outcome.wasCurrent) return

        val replacement = outcome.replacement
        if (replacement == null) stop() else startTrack(replacement)
    }

    /**
     * Reorder the queue without interrupting playback.
     */
    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        queue.move(fromIndex, toIndex)
    }

    /**
     * Clear the queue and stop.
     */
    fun clearQueue() {
        queue.clear()
        stop()
    }

    fun play() {
        when (_state) {
            is PlaybackState.Paused -> resume()
            is PlaybackState.Idle,
            is PlaybackState.Stopped,
            is PlaybackState.Error -> {
                val track = queue.currentTrack ?: return
                startTrack(track)
            }
            is PlaybackState.Playing -> Unit
            is PlaybackState.Loading -> Unit
        }
    }

    fun pause() {
        val currentState = state
        if (currentState is PlaybackState.Playing && playbackSink.pause()) {
            setState(
                PlaybackState.Paused(
                    trackPath = currentState.trackPath,
                    positionMs = playbackSink.positionMs
                )
            )
        }
    }

    fun stop() {
        playbackSink.stop()
        durationMs = 0L
        currentFormat = null
        setState(PlaybackState.Stopped)
    }

    fun seek(positionMs: Long) {
        val clampedPosition = positionMs.coerceIn(0L, durationMs)
        if (!playbackSink.seekTo(clampedPosition)) return

        when (val currentState = _state) {
            is PlaybackState.Playing -> {
                setState(currentState.copy(positionMs = clampedPosition))
            }
            is PlaybackState.Paused -> {
                setState(currentState.copy(positionMs = clampedPosition))
            }
            else -> Unit
        }
    }

    fun next() {
        val nextTrack = queue.next()
        if (nextTrack == null) stop() else startTrack(nextTrack)
    }

    fun previous() {
        if (playbackSink.positionMs > PREVIOUS_RESTART_THRESHOLD_MS) {
            seek(0L)
            return
        }
        val previousTrack = queue.previous() ?: return
        startTrack(previousTrack)
    }

    fun setRepeatMode(mode: RepeatMode) {
        queue.repeatMode = mode
    }

    fun getRepeatMode(): RepeatMode = queue.repeatMode

    fun toggleShuffle() {
        queue.setShuffle(!queue.isShuffleEnabled())
    }

    fun setShuffle(enabled: Boolean) {
        queue.setShuffle(enabled)
    }

    fun isShuffleEnabled(): Boolean = queue.isShuffleEnabled()

    fun setSleepTimer(durationMs: Long) {
        sleepTimer?.cancel()
        sleepTimer = SleepTimer(durationMs) { pause() }
        sleepTimer?.start()
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
    }

    fun getSleepTimerRemaining(): Long? = sleepTimer?.remainingMs

    /**
     * Whether a sleep timer is counting down.
     */
    fun isSleepTimerActive(): Boolean = sleepTimer?.active == true

    /**
     * Add time to a running sleep timer.
     */
    fun extendSleepTimer(additionalMs: Long) {
        sleepTimer?.extend(additionalMs)
    }

    /** Legacy native gapless callback; advance through the same output path. */
    fun onTrackTransition() {
        next()
    }

    /**
     * Retained for MediaSession/tests that provide an external position.
     * Real playback position comes from AudioTrack's playback head.
     */
    fun updatePosition(newPositionMs: Long) {
        playbackSink.overridePosition(newPositionMs.coerceIn(0L, durationMs))
    }

    fun release() {
        playbackSink.release()
        cancelSleepTimer()
        durationMs = 0L
        currentFormat = null
        setState(PlaybackState.Stopped)
        stateListeners.clear()
    }

    private fun resume() {
        val pausedState = state as? PlaybackState.Paused ?: return
        val format = currentFormat ?: return
        if (!playbackSink.resume()) return

        setState(
            PlaybackState.Playing(
                trackPath = pausedState.trackPath,
                positionMs = playbackSink.positionMs,
                durationMs = durationMs,
                format = format
            )
        )
    }

    private fun startTrack(trackPath: String) {
        setState(PlaybackState.Loading(trackPath))
        durationMs = 0L
        currentFormat = null
        playbackSink.play(trackPath)
    }

    private fun setState(newState: PlaybackState) {
        _state = newState
        stateListeners.forEach { listener -> listener(newState) }
    }

    private companion object {
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
    }
}
