package com.bitperfect.android.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.player.RepeatMode
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * MediaSessionManager - Manages the Media3 MediaSession for system-wide media controls.
 *
 * Responsibilities:
 * - Creates and configures androidx.media3.session.MediaSession
 * - Updates metadata (title, artist, album, artwork, duration)
 * - Updates playback state with current position
 * - Handles transport control callbacks via MediaSession.Callback
 * - Provides lock-screen artwork display
 * - Integrates with headset and Bluetooth media controls
 *
 * Uses a custom Player implementation that delegates commands to PlaybackController.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MediaSessionManager(
    private val context: Context,
    private val playbackController: PlaybackController
) {

    companion object {
        private const val TAG = "MediaSessionManager"
        private const val MEDIA_SESSION_TAG = "BitPerfectMediaSession"
    }

    private val playerAdapter: PlaybackControllerPlayer = PlaybackControllerPlayer(playbackController)

    // Last published metadata, for logging and debugging only. The values the
    // system actually reads live in the player adapter's timeline window.
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentDurationMs: Long = 0L

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Accept all connections
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                )
                .setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            Log.d(TAG, "MediaSession callback: onCustomCommand(${customCommand.customAction})")
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private val mediaSession: MediaSession

    /**
     * Decodes the cover for the platform session. See [SessionArtworkBitmapLoader]
     * for why the default one cannot be used: it opens URIs in a way MediaStore
     * refuses for album art, so its URI fallback could never succeed here.
     */
    private val artworkBitmapLoader = SessionArtworkBitmapLoader(context)

    init {
        mediaSession = MediaSession.Builder(context, playerAdapter)
            .setId(MEDIA_SESSION_TAG)
            .setCallback(mediaSessionCallback)
            // Not optional. media3 publishes artwork to the platform session only
            // via a BitmapLoader, and the default cannot read this app's URIs.
            .setBitmapLoader(artworkBitmapLoader)
            .build()

        Log.i(TAG, "MediaSession configured and active")
    }

    /**
     * Get the session compat token for binding to notification and service.
     * Returns a MediaSessionCompat.Token for backward compatibility with
     * notification builders and MediaBrowserServiceCompat.
     */
    fun getSessionToken(): MediaSessionCompat.Token = mediaSession.sessionCompatToken

    /**
     * Get the underlying Media3 MediaSession.
     */
    fun getSession(): MediaSession = mediaSession

    /**
     * Publish what is playing to the lock screen, the notification shade and any
     * vendor media widget.
     *
     * Everything the system shows comes from here. Before this was wired up the
     * session carried [MediaMetadata.EMPTY] for the whole life of the process,
     * which is why the panel read "Unknown song" with no cover and no times.
     *
     * @param trackPath identifies the item; also used as the timeline window's id
     *   so the system can tell one track from the next.
     * @param artworkUri a URI the system can read itself, or null.
     * @param artworkData cover bytes, for artwork the system cannot reach — see
     *   [ArtworkSource]. Passed by value, so it must already be downscaled.
     */
    fun updateMetadata(
        trackPath: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        artworkUri: Uri? = null,
        artworkData: ByteArray? = null
    ) {
        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentDurationMs = durationMs

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            // Some system UIs read the display fields rather than the tag fields,
            // so both are populated with the same values.
            .setDisplayTitle(title)
            .setSubtitle(artist)
            .setDescription(album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            // Note: media3 1.2.1's MediaMetadata has no duration field at all —
            // duration reaches the system purely through the timeline window, which
            // is why an empty timeline showed `--:--` however complete the metadata
            // was. SingleItemTimeline carries it.
            .apply {
                artworkUri?.let { setArtworkUri(it) }
                artworkData?.let {
                    setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            // A stable id per track: media3 uses it to decide whether the item
            // changed, and vendor widgets use it to reset their animation.
            .setMediaId(trackPath)
            .setUri(trackPath)
            .setMediaMetadata(metadata)
            .build()

        playerAdapter.updateMediaItem(mediaItem, durationMs)

        Log.d(TAG, "Metadata published: $title - $artist ($album), ${durationMs}ms")
    }

    /**
     * Update playback state based on the current PlaybackState.
     */
    fun updatePlaybackState(state: PlaybackState) {
        playerAdapter.updateFromPlaybackState(state)
    }

    /**
     * Release the media session and all associated resources.
     */
    fun release() {
        mediaSession.release()
        artworkBitmapLoader.release()
        Log.i(TAG, "MediaSession released")
    }



    /**
     * Custom Player implementation that bridges Media3 session commands
     * to the PlaybackController.
     *
     * Media3 requires a Player instance for the MediaSession. This adapter
     * translates Player interface calls into PlaybackController operations
     * and maintains state that the MediaSession can query.
     */
    private class PlaybackControllerPlayer(
        private val controller: PlaybackController
    ) : Player {

        private val listeners = mutableListOf<Player.Listener>()

        /**
         * The item being played, as a one-window timeline.
         *
         * media3 derives the duration and the metadata it publishes to the system
         * from the **timeline window**, not from [getDuration]. While this was
         * `Timeline.EMPTY` there was no window to read, so the panel showed
         * `--:--` at both ends and a scrubber that could not move.
         */
        private var timeline: Timeline = Timeline.EMPTY
        private var currentMediaItem: MediaItem? = null

        /**
         * Duration from the library's own metadata.
         *
         * Needed because the engine only learns a file's duration when it opens
         * it: a restored session sits in Paused with nothing prepared, and would
         * otherwise report no duration at all until the user pressed play.
         */
        private var metadataDurationMs: Long = 0L

        private var currentState: Int = Player.STATE_IDLE
        private var isPlaying: Boolean = false

        /**
         * Intent to play, which is not the same as playing.
         *
         * It stays true while a track is being opened, so the panel keeps showing a
         * pause button through a track change instead of blinking to "play" for as
         * long as the next file takes to open.
         */
        private var playWhenReady: Boolean = false
        private var currentPlaybackSpeed: Float = 1.0f
        private var shuffleEnabled: Boolean = false
        private var currentRepeatMode: Int = Player.REPEAT_MODE_OFF

        /**
         * Duration to report: whatever the engine knows, falling back to the
         * library's value before the file has been opened.
         */
        private val effectiveDurationMs: Long
            get() = controller.currentDurationMs.takeIf { it > 0L } ?: metadataDurationMs

        fun updateMediaItem(mediaItem: MediaItem, durationMs: Long) {
            val previousItem = currentMediaItem
            currentMediaItem = mediaItem
            metadataDurationMs = durationMs
            timeline = SingleItemTimeline(mediaItem, effectiveDurationMs)

            // All three, deliberately. media3 republishes to the system from
            // different callbacks depending on what it thinks changed, and a
            // missing one leaves the old title or the old duration on screen.
            listeners.forEach { listener ->
                listener.onTimelineChanged(
                    timeline,
                    Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED
                )
                if (previousItem?.mediaId != mediaItem.mediaId) {
                    listener.onMediaItemTransition(
                        mediaItem,
                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    )
                }
                listener.onMediaMetadataChanged(mediaItem.mediaMetadata)
            }
        }

        fun updateFromPlaybackState(state: PlaybackState) {
            val previousState = currentState
            val wasPlaying = isPlaying
            val previousPlayWhenReady = playWhenReady

            when (state) {
                is PlaybackState.Playing -> {
                    currentState = Player.STATE_READY
                    isPlaying = true
                    playWhenReady = true
                }
                is PlaybackState.Paused -> {
                    currentState = Player.STATE_READY
                    isPlaying = false
                    playWhenReady = false
                }
                is PlaybackState.Loading -> {
                    currentState = Player.STATE_BUFFERING
                    isPlaying = false
                    // Opening a file is on the way to playing it.
                    playWhenReady = true
                }
                is PlaybackState.Stopped -> {
                    currentState = Player.STATE_ENDED
                    isPlaying = false
                    playWhenReady = false
                }
                is PlaybackState.Error -> {
                    currentState = Player.STATE_IDLE
                    isPlaying = false
                    playWhenReady = false
                    val error = PlaybackException(
                        state.message,
                        null,
                        PlaybackException.ERROR_CODE_UNSPECIFIED
                    )
                    listeners.forEach { it.onPlayerError(error) }
                }
                is PlaybackState.Idle -> {
                    currentState = Player.STATE_IDLE
                    isPlaying = false
                    playWhenReady = false
                }
            }

            // The engine may only now have learned the real duration, which
            // arrives with the first Playing state. Refresh the window so the
            // scrubber gets a length instead of staying blank.
            refreshTimelineDuration()

            if (previousState != currentState) {
                listeners.forEach { it.onPlaybackStateChanged(currentState) }
            }
            if (previousPlayWhenReady != playWhenReady) {
                listeners.forEach {
                    it.onPlayWhenReadyChanged(
                        playWhenReady,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                    )
                }
            }
            if (wasPlaying != isPlaying) {
                // Drives the system's own position extrapolation: it takes the
                // position, the speed and a timestamp, then animates the bar
                // itself, which is why no per-second tick is needed here.
                listeners.forEach { it.onIsPlayingChanged(isPlaying) }
            }
        }

        /** Rebuild the window when the known duration changes. */
        private fun refreshTimelineDuration() {
            val item = currentMediaItem ?: return
            val duration = effectiveDurationMs
            if (duration <= 0L) return

            val window = Timeline.Window()
            val published = if (timeline.windowCount > 0) {
                timeline.getWindow(0, window).durationUs
            } else {
                -1L
            }
            if (published == duration * 1000L) return

            timeline = SingleItemTimeline(item, duration)
            listeners.forEach {
                it.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
            }
        }

        // --- Player interface implementation ---

        override fun getApplicationLooper(): Looper = Looper.getMainLooper()

        override fun addListener(listener: Player.Listener) {
            listeners.add(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners.remove(listener)
        }

        override fun setMediaItems(mediaItems: MutableList<MediaItem>) {}
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {}
        override fun setMediaItems(
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ) {}
        override fun setMediaItem(mediaItem: MediaItem) {}
        override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {}
        override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {}
        override fun addMediaItem(mediaItem: MediaItem) {}
        override fun addMediaItem(index: Int, mediaItem: MediaItem) {}
        override fun addMediaItems(mediaItems: MutableList<MediaItem>) {}
        override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {}
        override fun moveMediaItem(currentIndex: Int, newIndex: Int) {}
        override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {}
        override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {}
        override fun replaceMediaItems(
            fromIndex: Int,
            toIndex: Int,
            mediaItems: MutableList<MediaItem>
        ) {}
        override fun removeMediaItem(index: Int) {}
        override fun removeMediaItems(fromIndex: Int, toIndex: Int) {}
        override fun clearMediaItems() {}

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                // Without this media3 refuses to read the timeline, and the
                // timeline is where it gets the duration and the metadata it
                // publishes — so the panel had no title and no track length even
                // once both were available.
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA -> true
                else -> false
            }
        }

        /**
         * Must be true. media3's MediaSession.Builder asserts this in its
         * constructor via checkArgument(player.canAdvertiseSession()), so
         * returning false threw IllegalArgumentException and took the whole
         * service — and with it the app — down the moment playback started.
         *
         * True is also the honest answer: this Player exists precisely to back a
         * MediaSession, and it implements the transport commands it advertises in
         * getAvailableCommands().
         */
        override fun canAdvertiseSession(): Boolean = true

        override fun getAvailableCommands(): Player.Commands {
            return Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_STOP)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_METADATA)
                .build()
        }

        override fun prepare() {}
        override fun getPlaybackState(): Int = currentState

        override fun getPlaybackSuppressionReason(): Int =
            Player.PLAYBACK_SUPPRESSION_REASON_NONE

        override fun isPlaying(): Boolean = isPlaying

        override fun getPlayerError(): PlaybackException? = null

        override fun play() {
            Log.d(TAG, "Player adapter: play")
            controller.play()
        }

        override fun pause() {
            Log.d(TAG, "Player adapter: pause")
            controller.pause()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) play() else pause()
        }

        override fun getPlayWhenReady(): Boolean = playWhenReady

        override fun setRepeatMode(repeatMode: Int) {
            Log.d(TAG, "Player adapter: setRepeatMode($repeatMode)")
            currentRepeatMode = repeatMode
            val mode = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
            controller.setRepeatMode(mode)
            listeners.forEach { it.onRepeatModeChanged(repeatMode) }
        }

        override fun getRepeatMode(): Int = currentRepeatMode

        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
            Log.d(TAG, "Player adapter: setShuffleModeEnabled($shuffleModeEnabled)")
            shuffleEnabled = shuffleModeEnabled
            controller.setShuffle(shuffleModeEnabled)
            listeners.forEach { it.onShuffleModeEnabledChanged(shuffleModeEnabled) }
        }

        override fun getShuffleModeEnabled(): Boolean = shuffleEnabled

        override fun isLoading(): Boolean = currentState == Player.STATE_BUFFERING

        override fun seekToDefaultPosition() {
            seekTo(0L)
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            seekTo(0L)
        }

        override fun seekTo(positionMs: Long) {
            Log.d(TAG, "Player adapter: seekTo($positionMs)")
            val item = currentMediaItem
            val from = Player.PositionInfo(
                /* windowUid= */ SingleItemTimeline.WINDOW_UID,
                /* mediaItemIndex= */ 0,
                /* mediaItem= */ item,
                /* periodUid= */ SingleItemTimeline.WINDOW_UID,
                /* periodIndex= */ 0,
                /* positionMs= */ controller.currentPositionMs,
                /* contentPositionMs= */ controller.currentPositionMs,
                /* adGroupIndex= */ C.INDEX_UNSET,
                /* adIndexInAdGroup= */ C.INDEX_UNSET
            )

            controller.seek(positionMs)

            val to = Player.PositionInfo(
                SingleItemTimeline.WINDOW_UID,
                0,
                item,
                SingleItemTimeline.WINDOW_UID,
                0,
                positionMs,
                positionMs,
                C.INDEX_UNSET,
                C.INDEX_UNSET
            )

            // Republishes the position to the system, so dragging the scrubber in
            // the shade jumps the bar instead of snapping back until the next
            // state change.
            listeners.forEach {
                it.onPositionDiscontinuity(from, to, Player.DISCONTINUITY_REASON_SEEK)
            }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            seekTo(positionMs)
        }

        override fun getSeekBackIncrement(): Long = 10000L
        override fun seekBack() {
            seekTo(maxOf(0L, controller.currentPositionMs - getSeekBackIncrement()))
        }

        override fun getSeekForwardIncrement(): Long = 10000L
        override fun seekForward() {
            seekTo(controller.currentPositionMs + getSeekForwardIncrement())
        }

        @Deprecated("Use hasPreviousMediaItem() instead")
        override fun hasPrevious(): Boolean = true
        @Deprecated("Use hasPreviousMediaItem() instead")
        override fun hasPreviousWindow(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true

        @Deprecated("Use seekToPreviousMediaItem() instead")
        override fun previous() {
            seekToPreviousMediaItem()
        }
        @Deprecated("Use seekToPreviousMediaItem() instead")
        override fun seekToPreviousWindow() {
            seekToPreviousMediaItem()
        }
        override fun seekToPreviousMediaItem() {
            Log.d(TAG, "Player adapter: seekToPreviousMediaItem")
            controller.previous()
        }
        override fun seekToPrevious() {
            controller.previous()
        }

        override fun getMaxSeekToPreviousPosition(): Long = 3000L

        @Deprecated("Use hasNextMediaItem() instead")
        override fun hasNext(): Boolean = true
        @Deprecated("Use hasNextMediaItem() instead")
        override fun hasNextWindow(): Boolean = true
        override fun hasNextMediaItem(): Boolean = true

        @Deprecated("Use seekToNextMediaItem() instead")
        override fun next() {
            seekToNextMediaItem()
        }
        @Deprecated("Use seekToNextMediaItem() instead")
        override fun seekToNextWindow() {
            seekToNextMediaItem()
        }
        override fun seekToNextMediaItem() {
            Log.d(TAG, "Player adapter: seekToNextMediaItem")
            controller.next()
        }
        override fun seekToNext() {
            controller.next()
        }

        override fun setPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) {
            currentPlaybackSpeed = playbackParameters.speed
        }

        override fun setPlaybackSpeed(speed: Float) {
            currentPlaybackSpeed = speed
        }

        override fun getPlaybackParameters(): androidx.media3.common.PlaybackParameters =
            androidx.media3.common.PlaybackParameters(currentPlaybackSpeed)

        override fun stop() {
            Log.d(TAG, "Player adapter: stop")
            controller.stop()
        }

        override fun release() {
            // PlaybackController lifecycle is managed externally
        }

        override fun getCurrentTracks(): androidx.media3.common.Tracks = androidx.media3.common.Tracks.EMPTY

        override fun getTrackSelectionParameters(): androidx.media3.common.TrackSelectionParameters =
            androidx.media3.common.TrackSelectionParameters.Builder(/* context intentionally not stored */).build()

        override fun setTrackSelectionParameters(parameters: androidx.media3.common.TrackSelectionParameters) {}

        override fun getMediaMetadata(): MediaMetadata =
            currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY

        override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

        override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {}

        override fun getCurrentManifest(): Any? = null

        override fun getCurrentTimeline(): Timeline = timeline

        override fun getCurrentPeriodIndex(): Int = 0

        @Deprecated("Use getCurrentMediaItemIndex() instead")
        override fun getCurrentWindowIndex(): Int = 0
        override fun getCurrentMediaItemIndex(): Int = 0

        @Deprecated("Use getNextMediaItemIndex() instead")
        override fun getNextWindowIndex(): Int = 0
        override fun getNextMediaItemIndex(): Int = 0

        @Deprecated("Use getPreviousMediaItemIndex() instead")
        override fun getPreviousWindowIndex(): Int = 0
        override fun getPreviousMediaItemIndex(): Int = 0

        override fun getCurrentMediaItem(): MediaItem? = currentMediaItem

        override fun getMediaItemCount(): Int = if (currentMediaItem != null) 1 else 0

        override fun getMediaItemAt(index: Int): MediaItem =
            currentMediaItem ?: MediaItem.EMPTY

        override fun getDuration(): Long =
            effectiveDurationMs.takeIf { it > 0L } ?: C.TIME_UNSET

        /**
         * Read straight from the output rather than from a cached snapshot.
         *
         * The old field was only refreshed on discrete state transitions, so
         * whenever media3 asked for the position — which is exactly when it
         * publishes to the system — it got a value from the last play or pause.
         * The system extrapolates from the position it is given, so a stale one
         * produced a bar frozen wherever playback last changed state.
         */
        override fun getCurrentPosition(): Long = controller.currentPositionMs

        override fun getBufferedPosition(): Long = controller.currentPositionMs

        override fun getBufferedPercentage(): Int {
            val duration = effectiveDurationMs
            return if (duration > 0) {
                ((controller.currentPositionMs * 100) / duration).toInt()
            } else {
                0
            }
        }

        override fun getTotalBufferedDuration(): Long = 0L

        @Deprecated("Use isCurrentMediaItemDynamic() instead")
        override fun isCurrentWindowDynamic(): Boolean = false
        override fun isCurrentMediaItemDynamic(): Boolean = false

        @Deprecated("Use isCurrentMediaItemLive() instead")
        override fun isCurrentWindowLive(): Boolean = false
        override fun isCurrentMediaItemLive(): Boolean = false

        override fun getCurrentLiveOffset(): Long = 0L

        @Deprecated("Use isCurrentMediaItemSeekable() instead")
        override fun isCurrentWindowSeekable(): Boolean = true
        override fun isCurrentMediaItemSeekable(): Boolean = true

        override fun isPlayingAd(): Boolean = false
        override fun getCurrentAdGroupIndex(): Int = -1
        override fun getCurrentAdIndexInAdGroup(): Int = -1

        override fun getContentDuration(): Long =
            effectiveDurationMs.takeIf { it > 0L } ?: C.TIME_UNSET
        override fun getContentPosition(): Long = controller.currentPositionMs
        override fun getContentBufferedPosition(): Long = controller.currentPositionMs

        override fun getAudioAttributes(): androidx.media3.common.AudioAttributes =
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

        override fun setVolume(volume: Float) {}
        override fun getVolume(): Float = 1.0f

        override fun clearVideoSurface() {}
        override fun clearVideoSurface(surface: android.view.Surface?) {}
        override fun setVideoSurface(surface: android.view.Surface?) {}
        override fun setVideoSurfaceHolder(surfaceHolder: android.view.SurfaceHolder?) {}
        override fun clearVideoSurfaceHolder(surfaceHolder: android.view.SurfaceHolder?) {}
        override fun setVideoSurfaceView(surfaceView: android.view.SurfaceView?) {}
        override fun clearVideoSurfaceView(surfaceView: android.view.SurfaceView?) {}
        override fun setVideoTextureView(textureView: android.view.TextureView?) {}
        override fun clearVideoTextureView(textureView: android.view.TextureView?) {}

        override fun getVideoSize(): androidx.media3.common.VideoSize = androidx.media3.common.VideoSize.UNKNOWN

        override fun getSurfaceSize(): androidx.media3.common.util.Size = androidx.media3.common.util.Size(0, 0)

        override fun getCurrentCues(): androidx.media3.common.text.CueGroup =
            androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO

        override fun getDeviceInfo(): androidx.media3.common.DeviceInfo =
            androidx.media3.common.DeviceInfo.UNKNOWN

        override fun getDeviceVolume(): Int = 0
        override fun isDeviceMuted(): Boolean = false
        override fun setDeviceVolume(volume: Int) {}
        override fun setDeviceVolume(volume: Int, flags: Int) {}
        override fun increaseDeviceVolume() {}
        override fun increaseDeviceVolume(flags: Int) {}
        override fun decreaseDeviceVolume() {}
        override fun decreaseDeviceVolume(flags: Int) {}
        override fun setDeviceMuted(muted: Boolean) {}
        override fun setDeviceMuted(muted: Boolean, flags: Int) {}

        override fun setAudioAttributes(
            audioAttributes: androidx.media3.common.AudioAttributes,
            handleAudioFocus: Boolean
        ) {}
    }
}
