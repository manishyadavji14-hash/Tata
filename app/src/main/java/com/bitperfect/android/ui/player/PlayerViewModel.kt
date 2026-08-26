package com.bitperfect.android.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.engine.DsdManager
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.player.AudioFormatInfo
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.player.RepeatMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PlayerViewModel - ViewModel for the player screen.
 *
 * Responsibilities:
 * - Observes PlaybackController state changes
 * - Formats display strings (duration, format info, mode badge)
 * - Handles user interactions (play, pause, seek, next, previous)
 * - Exposes UI state as StateFlow for Compose observation
 * - Periodically updates position for seek bar
 */
class PlayerViewModel(
    private val playbackController: PlaybackController,
    private val engine: NativeAudioEngine,
    private val dsdManager: DsdManager
) : ViewModel() {

    /**
     * UI state for the player screen.
     */
    data class PlayerUiState(
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val isLoading: Boolean = false,
        val trackTitle: String = "No track",
        val artist: String = "",
        val album: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val positionText: String = "0:00",
        val durationText: String = "0:00",
        val formatBadge: String = "BITPERFECT",
        val formatDetail: String = "",
        val outputMode: OutputMode = OutputMode.BITPERFECT,
        val sampleRate: Int = 0,
        val bitDepth: Int = 0,
        val channels: Int = 0,
        val isShuffleEnabled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.OFF,
        val hasNext: Boolean = false,
        val hasPrevious: Boolean = false,
        val artworkUri: String? = null,
        val bufferLevel: Float = 0f,
        val deviceName: String = ""
    )

    /**
     * Output mode for display badge.
     */
    enum class OutputMode {
        BITPERFECT,
        PCM,
        DOP,
        NATIVE_DSD
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        // Listen for playback state changes
        playbackController.addStateListener { state ->
            updateUiState(state)
        }

        // Start position update loop
        viewModelScope.launch {
            while (isActive) {
                updatePositionIfPlaying()
                delay(250L) // Update 4 times per second
            }
        }
    }

    // --- User Actions ---

    fun play() {
        playbackController.play()
    }

    fun pause() {
        playbackController.pause()
    }

    fun togglePlayPause() {
        val state = playbackController.state
        if (state is PlaybackState.Playing) {
            pause()
        } else {
            play()
        }
    }

    fun next() {
        playbackController.next()
    }

    fun previous() {
        playbackController.previous()
    }

    fun seekTo(positionMs: Long) {
        playbackController.seek(positionMs)
    }

    fun toggleShuffle() {
        playbackController.toggleShuffle()
        _uiState.value = _uiState.value.copy(
            isShuffleEnabled = playbackController.isShuffleEnabled()
        )
    }

    fun cycleRepeatMode() {
        val nextMode = when (playbackController.getRepeatMode()) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        playbackController.setRepeatMode(nextMode)
        _uiState.value = _uiState.value.copy(repeatMode = nextMode)
    }

    // --- State Updates ---

    private fun updateUiState(state: PlaybackState) {
        val currentState = _uiState.value
        val newState = when (state) {
            is PlaybackState.Playing -> {
                val formatInfo = buildFormatDisplay(state.format)
                currentState.copy(
                    isPlaying = true,
                    isPaused = false,
                    isLoading = false,
                    trackTitle = extractTrackTitle(state.trackPath),
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    positionText = formatTime(state.positionMs),
                    durationText = formatTime(state.durationMs),
                    formatBadge = formatInfo.badge,
                    formatDetail = formatInfo.detail,
                    outputMode = formatInfo.mode,
                    sampleRate = state.format.sampleRate,
                    bitDepth = state.format.bitDepth,
                    channels = state.format.channels,
                    hasNext = playbackController.queue.hasNext(),
                    hasPrevious = playbackController.queue.hasPrevious(),
                    bufferLevel = engine.getBufferLevel(),
                    deviceName = engine.getDeviceName()
                )
            }
            is PlaybackState.Paused -> {
                currentState.copy(
                    isPlaying = false,
                    isPaused = true,
                    isLoading = false,
                    positionMs = state.positionMs,
                    positionText = formatTime(state.positionMs)
                )
            }
            is PlaybackState.Loading -> {
                currentState.copy(
                    isPlaying = false,
                    isPaused = false,
                    isLoading = true,
                    trackTitle = extractTrackTitle(state.trackPath)
                )
            }
            is PlaybackState.Stopped, is PlaybackState.Idle -> {
                PlayerUiState() // Reset to defaults
            }
            is PlaybackState.Error -> {
                currentState.copy(
                    isPlaying = false,
                    isPaused = false,
                    isLoading = false
                )
            }
        }
        _uiState.value = newState
    }

    private fun updatePositionIfPlaying() {
        val state = playbackController.state
        if (state is PlaybackState.Playing) {
            val currentPos = state.positionMs
            _uiState.value = _uiState.value.copy(
                positionMs = currentPos,
                positionText = formatTime(currentPos),
                bufferLevel = engine.getBufferLevel()
            )
        }
    }

    // --- Formatting ---

    /**
     * Format information for display badge.
     */
    private data class FormatDisplay(
        val badge: String,
        val detail: String,
        val mode: OutputMode
    )

    private fun buildFormatDisplay(format: AudioFormatInfo): FormatDisplay {
        val dsdMode = dsdManager.getCurrentMode()

        return when (dsdMode) {
            DsdManager.MODE_DOP -> {
                val dsdRate = dsdManager.getTransportRate()
                val dsdDesc = dsdManager.getDsdDescription(dsdRate)
                val transportRate = dsdManager.calculateDopRate(dsdRate)
                val transportKhz = formatFrequency(transportRate)
                FormatDisplay(
                    badge = "BITPERFECT",
                    detail = "$dsdDesc . DoP . $transportKhz . ${format.channels}ch",
                    mode = OutputMode.DOP
                )
            }
            DsdManager.MODE_NATIVE_DSD -> {
                val dsdRate = dsdManager.getTransportRate()
                val dsdDesc = dsdManager.getDsdDescription(dsdRate)
                val dsdMhz = formatDsdFrequency(dsdRate)
                FormatDisplay(
                    badge = "BITPERFECT",
                    detail = "$dsdDesc . Native DSD . $dsdMhz . ${format.channels}ch",
                    mode = OutputMode.NATIVE_DSD
                )
            }
            else -> {
                // PCM mode
                val codec = format.codec
                val khz = formatFrequency(format.sampleRate)
                FormatDisplay(
                    badge = "BITPERFECT",
                    detail = "$codec . ${format.bitDepth}-bit . $khz . ${format.channels}ch",
                    mode = OutputMode.PCM
                )
            }
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes >= 60) {
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            "%d:%02d:%02d".format(hours, remainingMinutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun formatFrequency(hz: Int): String {
        return when {
            hz >= 1000000 -> "${hz / 1000000.0} MHz"
            hz >= 1000 -> "${hz / 1000.0} kHz"
            else -> "$hz Hz"
        }
    }

    private fun formatDsdFrequency(hz: Int): String {
        val mhz = hz / 1000000.0
        return "%.4f MHz".format(mhz)
    }

    private fun extractTrackTitle(trackPath: String): String {
        return trackPath.substringAfterLast('/').substringBeforeLast('.')
    }

    override fun onCleared() {
        super.onCleared()
        playbackController.removeStateListener { }
    }
}
