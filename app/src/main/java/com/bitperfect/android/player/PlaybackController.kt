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
     * Null while a USB DAC is the output: platform effects bind to an AudioTrack
     * session, and applying them would stop the stream being bit-perfect.
     */
    val audioEffects: AudioEffectsController? get() = playbackSink.audioEffects

    /** Name of the output currently in use, for the UI. */
    val outputName: String get() = playbackSink.outputName

    /** Whether the current output delivers unmodified samples. */
    val isBitPerfectOutput: Boolean get() = playbackSink.isBitPerfect

    private var sleepTimer: SleepTimer? = null
    private val stateListeners = CopyOnWriteArrayList<(PlaybackState) -> Unit>()

    /**
     * Listening time per track, feeding the library's "most played" order.
     *
     * Owned here rather than in a ViewModel so it also counts playback driven
     * from the notification with no Activity on screen.
     */
    private val playStats = PlayStatsRecorder()

    /**
     * Persists accumulated listening time. Set by whoever has a library and a
     * scope that outlives an Activity; playback works normally when it is null,
     * it simply records nothing.
     */
    @Volatile
    var playStatsWriter: ((Map<String, Long>) -> Unit)? = null

    /**
     * Sample the current position into the play statistics.
     *
     * Called at every playback boundary below, and periodically by the UI so a
     * process kill mid-track loses only a few seconds. Safe to call at any time:
     * it credits only forward movement within one continuous stretch.
     */
    fun recordListeningSample() {
        val snapshot = _state
        val path = when (snapshot) {
            is PlaybackState.Playing -> snapshot.trackPath
            is PlaybackState.Paused -> snapshot.trackPath
            else -> null
        } ?: return
        playStats.sample(path, playbackSink.positionMs)
    }

    /**
     * Sample, then hand anything accumulated to [playStatsWriter].
     *
     * Called on the boundaries that end a stretch of listening, so the figure is
     * durable without writing to the database on every position tick.
     */
    private fun flushListening() {
        recordListeningSample()
        val pending = playStats.takePending()
        if (pending.isNotEmpty()) playStatsWriter?.invoke(pending)
    }

    /** Flush play statistics on demand, for the UI's periodic save. */
    fun flushPlayStats() = flushListening()

    @Volatile
    private var durationMs: Long = 0L

    @Volatile
    private var currentFormat: AudioFormatInfo? = null

    /**
     * Position a restored session must seek to once its track is prepared.
     * Consumed on first use, so a later manual play starts from the beginning.
     */
    @Volatile
    private var pendingSeekOnPrepareMs: Long = 0L

    /**
     * Shared by both outputs, so a track transition or an error is handled the
     * same way regardless of where the audio is going.
     */
    private val sinkListener = object : PlaybackSink.Listener {
            override fun onPrepared(
                trackPath: String,
                format: AudioFormatInfo,
                durationMs: Long
            ) {
                val loading = _state as? PlaybackState.Loading
                if (loading?.trackPath != trackPath) return

                this@PlaybackController.durationMs = durationMs
                currentFormat = format

                // Apply a restored position now that the file is open and the
                // duration is known, so resuming lands where the user left off.
                val restoreTo = pendingSeekOnPrepareMs
                pendingSeekOnPrepareMs = 0L
                val startAt = if (restoreTo > 0L && restoreTo < durationMs) {
                    playbackSink.seekTo(restoreTo)
                    restoreTo
                } else {
                    0L
                }

                // The file is open and may have been seeked to a restored
                // position, so this is where a stretch of listening begins.
                playStats.startSegment(trackPath, startAt)

                setState(
                    PlaybackState.Playing(
                        trackPath = trackPath,
                        positionMs = startAt,
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

                // Credit the run-out to the end of the file before moving on.
                // The last periodic sample lands slightly short of the end, and
                // without this a track played in full never quite reads 100%.
                if (durationMs > 0L) playStats.sample(trackPath, durationMs)
                flushListening()

                val nextTrack = queue.next()
                if (nextTrack == null) stop() else startTrack(nextTrack)
            }

            override fun onError(trackPath: String, message: String) {
                setState(PlaybackState.Error(message, trackPath))
            }
        }

    private val audioTrackSink = AudioTrackPlaybackSink(engine, sinkListener)
    private val usbSink = UsbPlaybackSink(engine, sinkListener)

    /**
     * The output for the current track.
     *
     * Chosen per track rather than once, because a DAC can be plugged in or
     * pulled out between tracks. It is deliberately not switched mid-track: the
     * sinks own their own worker threads and buffered audio, so swapping under a
     * running stream would drop or duplicate whatever is in flight.
     */
    @Volatile
    private var playbackSink: PlaybackSink = audioTrackSink

    /**
     * Pick the output for the next track.
     *
     * USB wins when a DAC is attached, because bit-perfect output is the reason
     * the app exists. Otherwise Android's mixer is the fallback so the app is
     * still usable with no DAC.
     */
    private fun selectSinkForNextTrack(): PlaybackSink =
        if (engine.isUsbDeviceAttached()) usbSink else audioTrackSink

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
     * Restore a saved session without starting playback.
     *
     * Puts the queue and current track back, shown paused at the saved position,
     * so reopening the app looks like where it was left and one tap resumes.
     * Deliberately does not auto-play: opening an app should not start making
     * noise by itself.
     */
    fun restoreSession(queue: List<String>, index: Int, positionMs: Long) {
        val playable = queue.filter { it.isNotBlank() }
        if (playable.isEmpty()) return

        this.queue.setQueue(playable, index.coerceIn(0, playable.lastIndex))
        val track = this.queue.currentTrack ?: return

        durationMs = 0L
        // Left null on purpose: it is how play() tells a restored session from a
        // genuinely paused one, since nothing has been decoded yet.
        currentFormat = null
        pendingSeekOnPrepareMs = positionMs
        setState(PlaybackState.Paused(trackPath = track, positionMs = positionMs))
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
        when (val snapshot = _state) {
            is PlaybackState.Paused -> {
                // A restored session shows as Paused but nothing has been opened
                // yet, so there is no sink state to resume. Start the track
                // instead; the saved position is applied once it is prepared.
                if (currentFormat == null) {
                    val track = queue.currentTrack ?: snapshot.trackPath
                    startTrack(track)
                } else {
                    resume()
                }
            }
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
        // Pausing ends a stretch of listening; bank it while the position is
        // still the one the user stopped at.
        if (currentState is PlaybackState.Playing) flushListening()
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
        flushListening()
        playbackSink.stop()
        durationMs = 0L
        currentFormat = null
        setState(PlaybackState.Stopped)
    }

    fun seek(positionMs: Long) {
        val clampedPosition = positionMs.coerceIn(0L, durationMs)

        // Bank what was listened to before the jump, then rebase. Without the
        // rebase, dragging the seek bar forward would be credited as listening.
        val seekingPath = when (val current = _state) {
            is PlaybackState.Playing -> current.trackPath
            is PlaybackState.Paused -> current.trackPath
            else -> null
        }
        if (seekingPath != null) playStats.sample(seekingPath, playbackSink.positionMs)

        if (!playbackSink.seekTo(clampedPosition)) return

        if (seekingPath != null) playStats.startSegment(seekingPath, clampedPosition)

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

    /**
     * Advance to the next track, or wrap to the first when at the end.
     *
     * This backs the mini player's swipe-forward gesture, where reaching the end
     * should loop to the start rather than stop. It differs from [next], which
     * stops at the end, and it does not touch the repeat mode.
     */
    fun skipToNextOrWrap() {
        val nextTrack = queue.next() ?: queue.jumpTo(0)
        if (nextTrack != null) startTrack(nextTrack)
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
        val clamped = newPositionMs.coerceIn(0L, durationMs)
        playbackSink.overridePosition(clamped)

        // An externally imposed position is a discontinuity like a seek, so
        // rebase rather than let the jump count as listening.
        val path = when (val current = _state) {
            is PlaybackState.Playing -> current.trackPath
            is PlaybackState.Paused -> current.trackPath
            else -> null
        }
        if (path != null) playStats.startSegment(path, clamped)
    }

    fun release() {
        flushListening()
        audioTrackSink.release()
        usbSink.release()
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
        // Bank the outgoing track's listening time before the state changes, or
        // skipping through a queue would lose it.
        flushListening()

        setState(PlaybackState.Loading(trackPath))
        durationMs = 0L
        currentFormat = null

        // Stop whatever was playing before deciding, so a switch of output does
        // not leave the previous sink's worker running.
        val nextSink = selectSinkForNextTrack()
        if (nextSink !== playbackSink) {
            playbackSink.stop()
            playbackSink = nextSink
        }

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
