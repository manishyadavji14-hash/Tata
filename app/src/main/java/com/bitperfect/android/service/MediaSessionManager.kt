package com.bitperfect.android.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState

/**
 * MediaSessionManager - Manages the MediaSession for system-wide media controls.
 *
 * Responsibilities:
 * - Creates and configures MediaSessionCompat
 * - Updates metadata (title, artist, album, artwork, duration)
 * - Updates playback state with current position
 * - Handles transport control callbacks (play, pause, stop, next, prev, seek)
 * - Provides lock-screen artwork display
 * - Integrates with headset and Bluetooth media controls
 */
class MediaSessionManager(
    private val context: Context,
    private val playbackController: PlaybackController
) {

    companion object {
        private const val TAG = "MediaSessionManager"
        private const val MEDIA_SESSION_TAG = "BitPerfectMediaSession"
    }

    private val mediaSession: MediaSessionCompat = MediaSessionCompat(context, MEDIA_SESSION_TAG)

    // Current metadata state
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentDurationMs: Long = 0L
    private var currentArtwork: Bitmap? = null

    init {
        configureSession()
    }

    private fun configureSession() {
        mediaSession.apply {
            // Set transport control flags
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            // Set callback for transport controls
            setCallback(mediaSessionCallback)

            // Set initial playback state
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 1.0f)
                    .setActions(getSupportedActions())
                    .build()
            )

            // Activate the session
            isActive = true
        }

        Log.i(TAG, "MediaSession configured and active")
    }

    /**
     * Get the session token for binding to notification and service.
     */
    fun getSessionToken(): MediaSessionCompat.Token = mediaSession.sessionToken

    /**
     * Get the underlying MediaSession.
     */
    fun getSession(): MediaSessionCompat = mediaSession

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

        val metadataBuilder = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, album)

            artwork?.let { bmp ->
                putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
                putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bmp)
            }
        }

        mediaSession.setMetadata(metadataBuilder.build())
        Log.d(TAG, "Metadata updated: $title - $artist ($album)")
    }

    /**
     * Update playback state based on the current PlaybackState.
     */
    fun updatePlaybackState(state: PlaybackState) {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(getSupportedActions())

        when (state) {
            is PlaybackState.Playing -> {
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    state.positionMs,
                    1.0f
                )
            }
            is PlaybackState.Paused -> {
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_PAUSED,
                    state.positionMs,
                    0.0f
                )
            }
            is PlaybackState.Loading -> {
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_BUFFERING,
                    0L,
                    0.0f
                )
            }
            is PlaybackState.Stopped -> {
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_STOPPED,
                    0L,
                    0.0f
                )
            }
            is PlaybackState.Error -> {
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_ERROR,
                    0L,
                    0.0f
                )
                stateBuilder.setErrorMessage(
                    PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR,
                    state.message
                )
            }
            is PlaybackState.Idle -> {
                stateBuilder.setState(
                    PlaybackStateCompat.STATE_NONE,
                    0L,
                    0.0f
                )
            }
        }

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    /**
     * Release the media session.
     */
    fun release() {
        mediaSession.isActive = false
        mediaSession.release()
        Log.i(TAG, "MediaSession released")
    }

    private fun getSupportedActions(): Long {
        return PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
            PlaybackStateCompat.ACTION_SET_REPEAT_MODE
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() {
            Log.d(TAG, "MediaSession callback: onPlay")
            playbackController.play()
        }

        override fun onPause() {
            Log.d(TAG, "MediaSession callback: onPause")
            playbackController.pause()
        }

        override fun onStop() {
            Log.d(TAG, "MediaSession callback: onStop")
            playbackController.stop()
        }

        override fun onSkipToNext() {
            Log.d(TAG, "MediaSession callback: onSkipToNext")
            playbackController.next()
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "MediaSession callback: onSkipToPrevious")
            playbackController.previous()
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "MediaSession callback: onSeekTo($pos)")
            playbackController.seek(pos)
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            Log.d(TAG, "MediaSession callback: onSetShuffleMode($shuffleMode)")
            val enabled = shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL ||
                shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_GROUP
            playbackController.setShuffle(enabled)
        }

        override fun onSetRepeatMode(repeatMode: Int) {
            Log.d(TAG, "MediaSession callback: onSetRepeatMode($repeatMode)")
            val mode = when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> com.bitperfect.android.player.RepeatMode.ONE
                PlaybackStateCompat.REPEAT_MODE_ALL,
                PlaybackStateCompat.REPEAT_MODE_GROUP -> com.bitperfect.android.player.RepeatMode.ALL
                else -> com.bitperfect.android.player.RepeatMode.OFF
            }
            playbackController.setRepeatMode(mode)
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            Log.d(TAG, "MediaSession callback: onMediaButtonEvent")
            return super.onMediaButtonEvent(mediaButtonEvent)
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            Log.d(TAG, "MediaSession callback: onCustomAction($action)")
        }
    }
}
