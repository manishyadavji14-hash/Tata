package com.bitperfect.android.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.engine.DsdManager
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.player.AudioFormatInfo
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.player.RepeatMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    internal val playbackController: PlaybackController,
    internal val engine: NativeAudioEngine,
    internal val dsdManager: DsdManager,
    private val musicLibrary: MusicLibrary
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
        val formatBadge: String = "ANDROID PCM",
        val formatDetail: String = "Select a WAV or FLAC file",
        val outputMode: OutputMode = OutputMode.PCM,
        val sampleRate: Int = 0,
        val bitDepth: Int = 0,
        val channels: Int = 0,
        val isShuffleEnabled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.OFF,
        val hasNext: Boolean = false,
        val hasPrevious: Boolean = false,
        val artworkUri: String? = null,
        val bufferLevel: Float = 0f,
        val deviceName: String = "",
        val errorMessage: String? = null,
        val isFavourite: Boolean = false,
        val isInLibrary: Boolean = false,
        val sleepTimerRemainingMs: Long? = null,
        val statusMessage: String? = null
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
    private val playbackStateListener: (PlaybackState) -> Unit = { state ->
        updateUiState(state)
    }

    /**
     * Path whose details have been requested, so a lookup is not repeated and a
     * late result for a superseded track can be discarded.
     */
    @Volatile
    private var detailsPath: String? = null

    /** Path whose lookup has actually completed and been applied to the UI. */
    @Volatile
    private var detailsResolvedPath: String? = null

    /**
     * Load title, artist, album and artwork for a track being played.
     */
    private fun resolveTrackDetails(trackPath: String) {
        if (detailsPath == trackPath) return
        detailsPath = trackPath

        viewModelScope.launch {
            val details = try {
                musicLibrary.getTrackDetails(trackPath)
            } catch (error: Exception) {
                MusicLibrary.TrackDetails(title = extractTrackTitle(trackPath))
            }

            // A different track started while this lookup was in flight.
            if (detailsPath != trackPath) return@launch

            detailsResolvedPath = trackPath
            _uiState.update { current ->
                current.copy(
                    isFavourite = details.isFavourite,
                    isInLibrary = details.isInLibrary,
                    trackTitle = details.title.ifBlank { extractTrackTitle(trackPath) },
                    artist = details.artist,
                    album = details.album,
                    artworkUri = details.artworkUri
                )
            }
        }
    }

    init {
        playbackController.addStateListener(playbackStateListener)

        // Start position update loop
        viewModelScope.launch {
            while (isActive) {
                updatePositionIfPlaying()
                delay(250L) // Update 4 times per second
            }
        }
    }

    // --- User Actions ---

    fun playFile(path: String) {
        playbackController.playFile(path)
    }

    /**
     * Toggle the favourite flag for the track being played.
     *
     * Only library tracks can be favourited; a file opened directly has no row
     * to mark, which is reported rather than silently ignored.
     */
    fun toggleFavourite() {
        val path = detailsResolvedPath ?: return
        viewModelScope.launch {
            val updated = musicLibrary.toggleFavouriteByPath(path)
            if (updated == null) {
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Add this file to your library to favourite it"
                    )
                }
                return@launch
            }
            _uiState.update { current ->
                current.copy(
                    isFavourite = updated,
                    statusMessage = if (updated) "Added to favourites" else "Removed from favourites"
                )
            }
        }
    }

    /**
     * Start a sleep timer.
     *
     * @param minutes Zero or less cancels any running timer.
     */
    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            playbackController.cancelSleepTimer()
            _uiState.update { current ->
                current.copy(
                    sleepTimerRemainingMs = null,
                    statusMessage = "Sleep timer off"
                )
            }
            return
        }

        playbackController.setSleepTimer(minutes * 60_000L)
        _uiState.update { current ->
            current.copy(
                sleepTimerRemainingMs = playbackController.getSleepTimerRemaining(),
                statusMessage = "Pausing in $minutes minutes"
            )
        }
    }

    /**
     * Add time to a running sleep timer.
     */
    fun extendSleepTimer(minutes: Int) {
        if (!playbackController.isSleepTimerActive()) return
        playbackController.extendSleepTimer(minutes * 60_000L)
        _uiState.update { current ->
            current.copy(
                sleepTimerRemainingMs = playbackController.getSleepTimerRemaining(),
                statusMessage = "Extended by $minutes minutes"
            )
        }
    }

    fun dismissStatusMessage() {
        _uiState.update { current -> current.copy(statusMessage = null) }
    }

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
        _uiState.update { current ->
            current.copy(
                isShuffleEnabled = playbackController.isShuffleEnabled()
            )
        }
    }

    fun cycleRepeatMode() {
        val nextMode = when (playbackController.getRepeatMode()) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        playbackController.setRepeatMode(nextMode)
        _uiState.update { current -> current.copy(repeatMode = nextMode) }
    }

    // --- State Updates ---

    private fun updateUiState(state: PlaybackState) {
        // This runs on the audio worker while the UI thread also updates state,
        // so the write below goes through `update` to avoid clobbering a
        // concurrent change. `update` may re-run its lambda under contention,
        // so the side effects and the bookkeeping they mutate are hoisted out
        // of it and performed exactly once here.
        val detailsMatchTrack = when (state) {
            is PlaybackState.Playing -> detailsResolvedPath == state.trackPath
            is PlaybackState.Loading -> detailsResolvedPath == state.trackPath
            else -> false
        }

        when (state) {
            is PlaybackState.Playing -> resolveTrackDetails(state.trackPath)
            is PlaybackState.Loading -> resolveTrackDetails(state.trackPath)
            is PlaybackState.Stopped, is PlaybackState.Idle -> {
                detailsPath = null
                detailsResolvedPath = null
            }
            is PlaybackState.Error, is PlaybackState.Paused -> Unit
        }

        _uiState.update { currentState ->
            when (state) {
                is PlaybackState.Playing -> {
                    val formatInfo = buildFormatDisplay(state.format)
                    currentState.copy(
                        isPlaying = true,
                        isPaused = false,
                        isLoading = false,
                        // Keep resolved tags if they belong to this track; otherwise
                        // show the file name until the lookup lands.
                        trackTitle = if (detailsMatchTrack) {
                            currentState.trackTitle
                        } else {
                            extractTrackTitle(state.trackPath)
                        },
                        artist = if (detailsMatchTrack) currentState.artist else "",
                        album = if (detailsMatchTrack) currentState.album else "",
                        artworkUri = if (detailsMatchTrack) currentState.artworkUri else null,
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
                        bufferLevel = 0f,
                        deviceName = "Android AudioTrack",
                        errorMessage = null
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
                    val isSameTrack = detailsMatchTrack
                    currentState.copy(
                        isPlaying = false,
                        isPaused = false,
                        isLoading = true,
                        trackTitle = if (isSameTrack) {
                            currentState.trackTitle
                        } else {
                            extractTrackTitle(state.trackPath)
                        },
                        // Do not carry the previous track's tags into a new load.
                        artist = if (isSameTrack) currentState.artist else "",
                        album = if (isSameTrack) currentState.album else "",
                        artworkUri = if (isSameTrack) currentState.artworkUri else null,
                        errorMessage = null
                    )
                }
                is PlaybackState.Stopped, is PlaybackState.Idle -> {
                    PlayerUiState() // Reset to defaults
                }
                is PlaybackState.Error -> {
                    currentState.copy(
                        isPlaying = false,
                        isPaused = false,
                        isLoading = false,
                        errorMessage = state.message
                    )
                }
            }
        }
    }

    private fun updatePositionIfPlaying() {
        val state = playbackController.state
        val sleepRemaining = playbackController.getSleepTimerRemaining()

        if (state is PlaybackState.Playing) {
            val currentPos = state.positionMs
            _uiState.update { current ->
                current.copy(
                    positionMs = currentPos,
                    positionText = formatTime(currentPos),
                    bufferLevel = 0f,
                    sleepTimerRemainingMs = sleepRemaining
                )
            }
        } else if (_uiState.value.sleepTimerRemainingMs != sleepRemaining) {
            _uiState.update { current -> current.copy(sleepTimerRemainingMs = sleepRemaining) }
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
        val dsdMode = try {
            dsdManager.getCurrentMode()
        } catch (e: Exception) {
            DsdManager.MODE_PCM
        }

        return when (dsdMode) {
            DsdManager.MODE_DOP -> {
                val dsdRate = try { dsdManager.getTransportRate() } catch (e: Exception) { 0 }
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
                val dsdRate = try { dsdManager.getTransportRate() } catch (e: Exception) { 0 }
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
                    badge = "ANDROID PCM",
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
        playbackController.removeStateListener(playbackStateListener)
        playbackController.release()
        super.onCleared()
    }
}
