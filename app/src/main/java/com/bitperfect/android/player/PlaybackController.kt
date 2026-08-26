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
