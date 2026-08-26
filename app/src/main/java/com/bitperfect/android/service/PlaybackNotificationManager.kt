package com.bitperfect.android.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.bitperfect.android.R
import com.bitperfect.android.player.PlaybackState

/**
 * PlaybackNotificationManager - Manages the media playback notification.
 *
 * Responsibilities:
 * - Builds media style notification with album artwork
 * - Provides play/pause, next, previous action buttons
 * - Creates ongoing notification for foreground service
 * - Updates notification on track or state changes
 * - Handles notification channel creation for Android 8+
 * - Shows current track info (title, artist, album art)
 */
class PlaybackNotificationManager(
    private val context: Context,
    private val channelId: String
) {

    companion object {
        private const val REQUEST_CODE_PLAY = 100
        private const val REQUEST_CODE_PAUSE = 101
        private const val REQUEST_CODE_NEXT = 102
        private const val REQUEST_CODE_PREVIOUS = 103
        private const val REQUEST_CODE_STOP = 104
        private const val REQUEST_CODE_CONTENT = 105
    }

    private var currentTitle: String = "BitPerfect"
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentArtwork: Bitmap? = null

    /**
     * Update the track metadata for notification display.
     */
    fun updateTrackInfo(
        title: String,
        artist: String,
        album: String,
        artwork: Bitmap? = null
    ) {
        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentArtwork = artwork
    }

    /**
     * Build the media notification based on current playback state.
     */
    fun buildNotification(
        state: PlaybackState,
        sessionToken: MediaSessionCompat.Token
    ): Notification {
        val isPlaying = state is PlaybackState.Playing

        val builder = NotificationCompat.Builder(context, channelId).apply {
            // Content
            setContentTitle(currentTitle)
            setContentText(currentArtist)
            setSubText(currentAlbum)
            setSmallIcon(R.drawable.ic_play)
            setOngoing(isPlaying)
            setShowWhen(false)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            // Album artwork
            currentArtwork?.let { setLargeIcon(it) }

            // Content intent - opens the app
            setContentIntent(getContentIntent())

            // Delete intent - stops playback
            setDeleteIntent(getActionIntent(PlaybackService.ACTION_STOP, REQUEST_CODE_STOP))

            // Media style
            setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(getActionIntent(PlaybackService.ACTION_STOP, REQUEST_CODE_STOP))
            )

            // Action buttons
            addAction(buildPreviousAction())
            if (isPlaying) {
                addAction(buildPauseAction())
            } else {
                addAction(buildPlayAction())
            }
            addAction(buildNextAction())

            // Priority
            priority = NotificationCompat.PRIORITY_LOW
            setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        }

        return builder.build()
    }

    private fun buildPlayAction(): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            R.drawable.ic_play,
            "Play",
            getActionIntent(PlaybackService.ACTION_PLAY, REQUEST_CODE_PLAY)
        ).build()
    }

    private fun buildPauseAction(): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            R.drawable.ic_pause,
            "Pause",
            getActionIntent(PlaybackService.ACTION_PAUSE, REQUEST_CODE_PAUSE)
        ).build()
    }

    private fun buildNextAction(): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            R.drawable.ic_next,
            "Next",
            getActionIntent(PlaybackService.ACTION_NEXT, REQUEST_CODE_NEXT)
        ).build()
    }

    private fun buildPreviousAction(): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            R.drawable.ic_previous,
            "Previous",
            getActionIntent(PlaybackService.ACTION_PREVIOUS, REQUEST_CODE_PREVIOUS)
        ).build()
    }

    private fun getActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).apply {
            this.action = action
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(context, requestCode, intent, flags)
    }

    private fun getContentIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, REQUEST_CODE_CONTENT, intent, flags)
    }
}
