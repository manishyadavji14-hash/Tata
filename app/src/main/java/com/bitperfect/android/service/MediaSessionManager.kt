package com.bitperfect.android.service

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
class MediaSessionManager(
    private val context: Context,
    private val playbackController: PlaybackController
) {

    companion object {
        private const val TAG = "MediaSessionManager"
        private const val MEDIA_SESSION_TAG = "BitPerfectMediaSession"
    }

    private val playerAdapter: PlaybackControllerPlayer = PlaybackControllerPlayer(playbackController)
    private val mediaSession: MediaSession

    // Current metadata state
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentDurationMs: Long = 0L
    private var currentArtwork: Bitmap? = null

    init {
        mediaSession = MediaSession.Builder(context, playerAdapter)
            .setId(MEDIA_SESSION_TAG)
            .setCallback(mediaSessionCallback)
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
     * Update metadata displayed on lock screen and notification.
     */
    fun updateMetadata(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        artwork: Bitmap? = null
    ) {
        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentDurationMs = durationMs
        currentArtwork = artwork

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDisplayTitle(title)
            .setSubtitle(artist)
            .setDescription(album)

        artwork?.let { bmp ->
            metadataBuilder.setArtworkData(
                bitmapToByteArray(bmp),
                MediaMetadata.PICTURE_TYPE_FRONT_COVER
            )
        }

        // Update the player adapter with new metadata and duration so that
        // the MediaSession reflects the current track information
        playerAdapter.updateMediaMetadata(metadataBuilder.build(), durationMs)

        Log.d(TAG, "Metadata updated: $title - $artist ($album)")
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
        Log.i(TAG, "MediaSession released")
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

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
        private var currentMediaMetadata: MediaMetadata = MediaMetadata.EMPTY
        private var currentDurationMs: Long = 0L
        private var currentPositionMs: Long = 0L
        private var currentState: Int = Player.STATE_IDLE
        private var isPlaying: Boolean = false
        private var currentPlaybackSpeed: Float = 1.0f
        private var shuffleEnabled: Boolean = false
        private var currentRepeatMode: Int = Player.REPEAT_MODE_OFF

        fun updateMediaMetadata(metadata: MediaMetadata, durationMs: Long) {
            currentMediaMetadata = metadata
            currentDurationMs = durationMs
            listeners.forEach { it.onMediaMetadataChanged(metadata) }
        }

        fun updateFromPlaybackState(state: PlaybackState) {
            val previousState = currentState
            val wasPlaying = isPlaying

            when (state) {
                is PlaybackState.Playing -> {
                    currentState = Player.STATE_READY
                    isPlaying = true
                    currentPositionMs = state.positionMs
                    currentDurationMs = state.durationMs
                }
                is PlaybackState.Paused -> {
                    currentState = Player.STATE_READY
                    isPlaying = false
                    currentPositionMs = state.positionMs
                }
                is PlaybackState.Loading -> {
                    currentState = Player.STATE_BUFFERING
                    isPlaying = false
                }
                is PlaybackState.Stopped -> {
                    currentState = Player.STATE_ENDED
                    isPlaying = false
                    currentPositionMs = 0L
                }
                is PlaybackState.Error -> {
                    currentState = Player.STATE_IDLE
                    isPlaying = false
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
                    currentPositionMs = 0L
                }
            }

            if (previousState != currentState) {
                listeners.forEach { it.onPlaybackStateChanged(currentState) }
            }
            if (wasPlaying != isPlaying) {
                listeners.forEach { it.onIsPlayingChanged(isPlaying) }
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
                Player.COMMAND_GET_METADATA -> true
                else -> false
            }
        }

        override fun canAdvertiseSession(): Boolean = false

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

        override fun getPlayWhenReady(): Boolean = isPlaying

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
            currentPositionMs = positionMs
            controller.seek(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            seekTo(positionMs)
        }

        override fun getSeekBackIncrement(): Long = 10000L
        override fun seekBack() {
            seekTo(maxOf(0L, currentPositionMs - getSeekBackIncrement()))
        }

        override fun getSeekForwardIncrement(): Long = 10000L
        override fun seekForward() {
            seekTo(currentPositionMs + getSeekForwardIncrement())
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

        override fun getMediaMetadata(): MediaMetadata = currentMediaMetadata

        override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

        override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {}

        override fun getCurrentManifest(): Any? = null

        override fun getCurrentTimeline(): Timeline = Timeline.EMPTY

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

        override fun getCurrentMediaItem(): MediaItem? {
            return if (currentMediaMetadata != MediaMetadata.EMPTY) {
                MediaItem.Builder()
                    .setMediaMetadata(currentMediaMetadata)
                    .build()
            } else {
                null
            }
        }

        override fun getMediaItemCount(): Int =
            if (currentMediaMetadata != MediaMetadata.EMPTY) 1 else 0

        override fun getMediaItemAt(index: Int): MediaItem =
            getCurrentMediaItem() ?: MediaItem.EMPTY

        override fun getDuration(): Long = currentDurationMs

        override fun getCurrentPosition(): Long = currentPositionMs

        override fun getBufferedPosition(): Long = currentPositionMs

        override fun getBufferedPercentage(): Int {
            return if (currentDurationMs > 0) {
                ((currentPositionMs * 100) / currentDurationMs).toInt()
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

        override fun getContentDuration(): Long = currentDurationMs
        override fun getContentPosition(): Long = currentPositionMs
        override fun getContentBufferedPosition(): Long = currentPositionMs

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

        override fun getSurfaceSize(): android.util.Size = android.util.Size(0, 0)

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
