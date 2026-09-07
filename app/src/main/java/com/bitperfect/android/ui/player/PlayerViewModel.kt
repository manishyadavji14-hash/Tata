package com.bitperfect.android.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.BitPerfectApp
import com.bitperfect.android.ServiceLocator
import com.bitperfect.android.engine.DsdManager
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.player.AudioEffectsController
import com.bitperfect.android.player.AudioFormatInfo
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.Lyrics
import com.bitperfect.android.player.LyricsRepository
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.player.PlaybackStateStore
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
    private val musicLibrary: MusicLibrary,
    /**
     * Remembers the session across app restarts. Optional so the ViewModel stays
     * constructible without a Context in tests.
     */
    private val sessionStore: PlaybackStateStore? = null,
    /**
     * Resolves lyrics from the user's own text, a sidecar file, or the file's
     * tags.
     *
     * Taken from the library rather than constructed here so it is the same
     * instance the library screen writes through: two instances would each keep
     * their own cache, and lyrics edited from a track's menu would not show up in
     * the player until the app restarted.
     */
    private val lyricsRepository: LyricsRepository = musicLibrary.lyricsRepository
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
        /** True when the current output delivers unmodified samples. */
        val isBitPerfectOutput: Boolean = false,
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
        val statusMessage: String? = null,

        /** Absolute path of the current file, for the Info sheet and actions. */
        val trackPath: String = "",

        // Navigation targets for the album-art overflow menu. Zero or blank when
        // the file is not in the library, and the menu hides those entries.
        val albumId: Long = 0L,
        val artistId: Long = 0L,
        val genre: String = "",
        val folder: String = "",
        val year: Int = 0,
        val trackNumber: Int = 0,
        val fileSize: Long = 0L,

        // --- Lyrics ---
        /** Lyrics for the current track, empty when none were found. */
        val lyrics: Lyrics = Lyrics.EMPTY,
        /** Whether the lyrics panel has replaced the title block. */
        val isLyricsVisible: Boolean = false,
        /** Index of the line matching the current position, -1 when none. */
        val currentLyricIndex: Int = -1,
        /**
         * User nudge in milliseconds for timings that run early or late. Applied
         * on top of any offset declared in the file.
         */
        val lyricsOffsetMs: Long = 0L
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

    /** Guards against several concurrent first-track lookups. */
    @Volatile
    private var isPreparingInitialTrack = false
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

            // Lyrics are looked up per track and cached by the repository, so a
            // track with none does not touch the filesystem again.
            val loadedLyrics = lyricsRepository.load(trackPath)

            detailsResolvedPath = trackPath
            _uiState.update { current ->
                current.copy(
                    isFavourite = details.isFavourite,
                    isInLibrary = details.isInLibrary,
                    lyrics = loadedLyrics,
                    currentLyricIndex = -1,
                    // A per-track nudge would be surprising to carry over, and
                    // the panel stays open across tracks, so reset it.
                    lyricsOffsetMs = 0L,
                    trackTitle = details.title.ifBlank { extractTrackTitle(trackPath) },
                    artist = details.artist,
                    album = details.album,
                    artworkUri = details.artworkUri,
                    // The path was declared on the state and never once assigned,
                    // which quietly disabled everything gated on it: the album-art
                    // overflow menu early-returns on an empty path, so Info/Tags,
                    // "add to playlist" and the go-to-album actions never appeared.
                    trackPath = trackPath,
                    // Same story for the rest of these — the Info dialog had
                    // nowhere to read them from.
                    albumId = details.albumId,
                    artistId = details.artistId,
                    genre = details.genre,
                    folder = details.folder,
                    year = details.year,
                    trackNumber = details.trackNumber,
                    fileSize = details.fileSize
                )
            }
        }
    }

    init {
        playbackController.addStateListener(playbackStateListener)

        // Listening time is written on the application scope, not this one.
        // Playback outlives the player screen — the notification keeps it going
        // after the Activity is gone — and a write cancelled with the ViewModel
        // would lose whatever had been counted.
        playbackController.playStatsWriter = { listenedByPath ->
            BitPerfectApp.applicationScope.launch {
                musicLibrary.addListenedMs(listenedByPath)
            }
        }

        // Put the last session back before anything else touches state.
        restoreSession()

        // Start position update loop
        viewModelScope.launch {
            var ticksSinceSave = 0
            while (isActive) {
                updatePositionIfPlaying()

                // Persist the position every few seconds rather than every tick:
                // often enough that a kill loses only a moment, rarely enough not
                // to write to disk four times a second.
                if (++ticksSinceSave >= POSITION_SAVE_INTERVAL_TICKS) {
                    ticksSinceSave = 0
                    if (_uiState.value.isPlaying) {
                        savePosition()
                        // Play statistics are exact from the playback boundaries
                        // alone; this only bounds how much is lost if the process
                        // is killed in the middle of a track.
                        playbackController.flushPlayStats()
                    }
                }
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

    /**
     * Show a message produced elsewhere in this screen's snackbar.
     *
     * The add-to-playlist dialog is hosted by the nav graph, not the player, so
     * without this its outcome — including "add this file to your library first"
     * — would be reported nowhere.
     */
    fun showExternalMessage(message: String) {
        _uiState.update { current -> current.copy(statusMessage = message) }
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

    /**
     * Swipe-forward from the mini player: advance, wrapping to the first track
     * when already at the end of the queue.
     */
    fun nextOrWrap() {
        playbackController.skipToNextOrWrap()
    }

    /**
     * Path of the track currently loaded, or null when nothing is playing.
     * Used to jump the library to the playing song.
     */
    fun currentTrackPath(): String? = when (val state = playbackController.state) {
        is PlaybackState.Playing -> state.trackPath
        is PlaybackState.Paused -> state.trackPath
        is PlaybackState.Loading -> state.trackPath
        else -> null
    }

    // --- Session persistence ---

    /**
     * Reload the last session without starting playback.
     *
     * The queue and track are restored and the player shows them paused at the
     * saved position, so reopening the app looks like where you left it and one
     * tap resumes. It deliberately does not auto-play: launching the app should
     * not start making noise on its own.
     */
    private fun restoreSession() {
        val store = sessionStore ?: return
        val snapshot = store.load() ?: return

        playbackController.restoreSession(
            queue = snapshot.queue,
            index = snapshot.queueIndex,
            positionMs = snapshot.positionMs
        )

        // Populate the UI from the restored track so it is visible before any
        // playback starts.
        resolveTrackDetails(snapshot.trackPath)
        _uiState.update { current ->
            current.copy(
                isPlaying = false,
                isPaused = true,
                positionMs = snapshot.positionMs,
                positionText = formatTime(snapshot.positionMs)
            )
        }
    }

    /**
     * Put the first library track on the player when there is nothing to show.
     *
     * On a first run there is no saved session, so the player sat on "No track"
     * with a dead transport and no mini player even though the library had just
     * been scanned. This loads the library as the queue, parked on its first
     * track, so the screen is populated and pressing play works — without
     * starting playback on its own, which launching an app should never do.
     *
     * Safe to call repeatedly: it does nothing once a track is loaded, which is
     * how it ends up running after the first scan rather than before it.
     */
    fun ensureInitialTrackLoaded() {
        if (_uiState.value.trackPath.isNotEmpty()) return
        if (isPreparingInitialTrack) return

        isPreparingInitialTrack = true
        viewModelScope.launch {
            try {
                val paths = musicLibrary.getAllTracks().map { it.path }
                if (paths.isEmpty()) return@launch

                // Something may have started playing while the library loaded;
                // never overwrite a real session with a parked one.
                if (_uiState.value.trackPath.isNotEmpty()) return@launch

                playbackController.restoreSession(queue = paths, index = 0, positionMs = 0L)
                resolveTrackDetails(paths.first())
            } finally {
                isPreparingInitialTrack = false
            }
        }
    }

    /**
     * What the audio pipeline is doing right now, for the player's info panel.
     *
     * Read on demand rather than pushed into [PlayerUiState]: these are counters
     * and engine queries, and polling them four times a second to keep a state
     * object fresh would cost more than the panel is worth.
     *
     * Every value is either measured or explicitly marked unknown. Nothing here is
     * a plausible-looking placeholder — the point of the panel is to answer "what
     * is actually happening", and a made-up number would defeat it.
     */
    fun audioPipelineInfo(): AudioPipelineInfo {
        val state = _uiState.value
        val effects = playbackController.audioEffects

        val underruns = runCatching { engine.getUnderrunCount() }.getOrNull()
        val bufferLevel = runCatching { engine.getBufferLevel() }.getOrNull()
        val transport = runCatching { engine.getTransportName() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        val usbActive = runCatching { engine.isUsbOutputActive() }.getOrDefault(false)
        val engineRate = runCatching { engine.getCurrentSampleRate() }.getOrNull()

        val effectsSummary = when {
            effects == null ->
                "Bypassed — not applied on a bit-perfect output"
            !effects.capabilities.isAvailable ->
                "Unavailable on this device"
            !effects.settings.isEnabled ->
                "Off"
            else -> buildList {
                add("Equalizer on")
                if (effects.settings.bassBoostStrength > 0) {
                    add("bass ${percentOf(effects.settings.bassBoostStrength)}")
                }
                if (effects.settings.trebleStrength > 0) {
                    add("treble ${percentOf(effects.settings.trebleStrength)}")
                }
            }.joinToString(", ")
        }

        return AudioPipelineInfo(
            trackTitle = state.trackTitle,
            container = state.formatBadge,
            sourceFormat = describeFormat(state.sampleRate, state.bitDepth, state.channels),
            decoder = describeDecoder(state.trackPath, state.outputMode),
            outputName = playbackController.outputName,
            outputMode = state.formatDetail,
            isBitPerfect = playbackController.isBitPerfectOutput,
            engineSampleRate = engineRate?.takeIf { it > 0 },
            effectsSummary = effectsSummary,
            transportName = transport,
            isUsbOutputActive = usbActive,
            bufferLevelPercent = bufferLevel?.takeIf { it in 0f..1f }?.let { (it * 100).toInt() },
            underrunCount = underruns,
            artworkPublishReport = ServiceLocator.artworkPublishReport.get()
        )
    }

    private fun percentOf(strength: Int): String =
        "${strength * 100 / AudioEffectsController.MAX_STRENGTH}%"

    private fun describeFormat(sampleRate: Int, bitDepth: Int, channels: Int): String {
        if (sampleRate <= 0) return "Unknown"
        val rate = if (sampleRate % 1000 == 0) {
            "${sampleRate / 1000} kHz"
        } else {
            "%.1f kHz".format(sampleRate / 1000.0)
        }
        return listOfNotNull(
            rate,
            bitDepth.takeIf { it > 0 }?.let { "$it-bit" },
            when (channels) {
                1 -> "mono"
                2 -> "stereo"
                in 3..64 -> "$channels ch"
                else -> null
            }
        ).joinToString(" · ")
    }

    /**
     * Which decoder the current track went through.
     *
     * The routing rule lives in PcmSourceFactory: the USB path only ever uses the
     * native decoders, because a platform-decoded stream cannot be called
     * bit-perfect. On the Android path everything except WAV goes to MediaCodec.
     */
    private fun describeDecoder(trackPath: String, outputMode: OutputMode): String {
        val extension = trackPath.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return "Unknown"

        val isNative = playbackController.isBitPerfectOutput ||
            extension == "wav" || extension == "wave"
        return if (isNative) {
            "BitPerfect native decoder"
        } else {
            "Android MediaCodec"
        }
    }

    /** A snapshot of the playback chain, for the player's audio info panel. */
    data class AudioPipelineInfo(
        val trackTitle: String,
        val container: String,
        val sourceFormat: String,
        val decoder: String,
        val outputName: String,
        val outputMode: String,
        val isBitPerfect: Boolean,
        val engineSampleRate: Int?,
        val effectsSummary: String,
        val transportName: String?,
        val isUsbOutputActive: Boolean,
        val bufferLevelPercent: Int?,
        val underrunCount: Int?,
        /**
         * Whether this track's cover reached the media session, in plain language.
         *
         * Reported because a missing lock-screen cover is invisible from inside the
         * app: the player can be showing one while the session has none, and the two
         * come from different code. Without this the only way to tell them apart is
         * a log the maintainer cannot read.
         */
        val artworkPublishReport: String
    )

    /** Persist the whole session. Called on track change and when clearing up. */
    private fun saveSession() {
        val store = sessionStore ?: return
        val path = currentTrackPath() ?: return
        store.save(
            trackPath = path,
            positionMs = _uiState.value.positionMs,
            queue = playbackController.queue.tracks,
            queueIndex = playbackController.queue.position
        )
    }

    // --- Lyrics ---

    /**
     * Show or hide the lyrics panel, which takes the place of the title block.
     *
     * Does nothing when there are no lyrics: the icon is hidden in that case, so
     * reaching here means state changed underneath the user.
     */
    fun toggleLyrics() {
        _uiState.update { current ->
            if (current.lyrics.isEmpty) current else current.copy(
                isLyricsVisible = !current.isLyricsVisible
            )
        }
    }

    /**
     * Nudge synced lyrics earlier or later, for files whose timings are off.
     *
     * @param deltaMs positive shows lines sooner
     */
    fun nudgeLyrics(deltaMs: Long) {
        _uiState.update { current ->
            val offset = (current.lyricsOffsetMs + deltaMs)
                .coerceIn(-MAX_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
            current.copy(
                lyricsOffsetMs = offset,
                currentLyricIndex = current.lyrics.indexAt(current.positionMs, offset)
            )
        }
    }

    fun resetLyricsOffset() {
        _uiState.update { current ->
            current.copy(
                lyricsOffsetMs = 0L,
                currentLyricIndex = current.lyrics.indexAt(current.positionMs, 0L)
            )
        }
    }

    /** Cheap position-only write for the periodic tick. */
    private fun savePosition() {
        sessionStore?.savePosition(_uiState.value.positionMs)
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

    // The sleep timer API lives above: setSleepTimer(minutes) and
    // extendSleepTimer(minutes), with the remaining time exposed through
    // uiState.sleepTimerRemainingMs so the UI re-renders as it counts down.
    // A millisecond-based setSleepTimer overload used to sit here too, which
    // made `setSleepTimer(0)` resolve by argument type rather than by meaning.

    /**
     * Play a track from the library.
     *
     * Replaces the queue with the list the track was shown in, so playback
     * continues through that list instead of stopping after the one tapped
     * track.
     *
     * @param tracks Track paths of the list the user was looking at
     * @param selectedIndex Index within [tracks] of the track the user tapped
     */
    fun playFromLibrary(tracks: List<String>, selectedIndex: Int) {
        playbackController.playQueue(tracks, selectedIndex)
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
            is PlaybackState.Playing -> {
                resolveTrackDetails(state.trackPath)
                // Persist the whole session on each track change, so being killed
                // in the background loses at most the last few seconds of
                // position rather than the queue.
                saveSession()
            }
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
                        // Read back from the queue rather than trusting the last
                        // button press. Replacing the queue can change these, and
                        // a shuffle icon that says "on" over a queue playing in
                        // order is worse than no icon at all.
                        isShuffleEnabled = playbackController.isShuffleEnabled(),
                        repeatMode = playbackController.getRepeatMode(),
                        bufferLevel = 0f,
                        // The real output, not a guess: this is "USB DAC" or the
                        // DAC's own name when one is attached.
                        deviceName = playbackController.outputName,
                        isBitPerfectOutput = playbackController.isBitPerfectOutput,
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
                    // Reset the track, but keep the settings the user chose.
                    // Shuffle, repeat and the sleep timer belong to the session,
                    // not to the track that just finished — wiping them meant
                    // reaching the end of a queue silently turned shuffle and
                    // repeat back off.
                    PlayerUiState(
                        isShuffleEnabled = playbackController.isShuffleEnabled(),
                        repeatMode = playbackController.getRepeatMode(),
                        sleepTimerRemainingMs = currentState.sleepTimerRemainingMs
                    )
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
                    sleepTimerRemainingMs = sleepRemaining,
                    // Recomputed on the same tick as the position, so the
                    // highlighted line and the seek bar never disagree. This is a
                    // binary search over the lines, not a scan.
                    currentLyricIndex = current.lyrics.indexAt(
                        currentPos,
                        current.lyricsOffsetMs
                    )
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
        // Save before tearing anything down, while the queue and position are
        // still readable.
        saveSession()
        playbackController.removeStateListener(playbackStateListener)
        playbackController.release()
        // This ViewModel owns the engine and controller published in the
        // ServiceLocator, so the reference is dropped here rather than in the
        // Activity: the Activity is destroyed on every rotation while this
        // ViewModel, and the engine it holds, survive.
        ServiceLocator.clearServiceReferences()
        super.onCleared()
    }

    private companion object {
        /** 250 ms ticks, so 20 ticks is a position write every 5 seconds. */
        const val POSITION_SAVE_INTERVAL_TICKS = 20

        /** Ten seconds either way is far more than any real file needs. */
        const val MAX_LYRICS_OFFSET_MS = 10_000L
    }
}
