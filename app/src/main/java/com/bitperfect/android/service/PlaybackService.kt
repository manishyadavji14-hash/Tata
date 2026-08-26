package com.bitperfect.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.usb.UsbAudioManager
import com.bitperfect.android.usb.UsbErrorRecovery
import com.bitperfect.android.usb.UsbPermissionHandler

/**
 * PlaybackService - Foreground service for audio playback.
 *
 * Extends MediaBrowserServiceCompat to provide:
 * - Foreground service with media notification
 * - MediaSession integration for lock-screen/headset/Bluetooth controls
 * - Audio focus management
 * - USB permission lifecycle management
 * - Screen-off playback support via WakeLock
 * - START_STICKY for service persistence
 * - Binds to NativeAudioEngine for bit-perfect output
 *
 * This service is the central coordinator for all playback operations.
 * It survives configuration changes and continues playing when the app
 * is in the background or the screen is off.
 */
class PlaybackService : MediaBrowserServiceCompat(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "bitperfect_playback"
        private const val CHANNEL_NAME = "BitPerfect Playback"
        private const val MEDIA_SESSION_TAG = "BitPerfectMediaSession"

        const val ACTION_PLAY = "com.bitperfect.android.action.PLAY"
        const val ACTION_PAUSE = "com.bitperfect.android.action.PAUSE"
        const val ACTION_STOP = "com.bitperfect.android.action.STOP"
        const val ACTION_NEXT = "com.bitperfect.android.action.NEXT"
        const val ACTION_PREVIOUS = "com.bitperfect.android.action.PREVIOUS"
    }

    // Core components
    private lateinit var engine: NativeAudioEngine
    private lateinit var playbackController: PlaybackController
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var notificationManager: PlaybackNotificationManager
    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var usbPermissionHandler: UsbPermissionHandler
    private lateinit var usbErrorRecovery: UsbErrorRecovery

    // Audio focus
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hadAudioFocusBeforeTransientLoss = false

    // Wake lock for screen-off playback
    private var wakeLock: PowerManager.WakeLock? = null

    // Service binder for local binding
    private val binder = PlaybackBinder()

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
        fun getPlaybackController(): PlaybackController = playbackController
        fun getEngine(): NativeAudioEngine = engine
    }

    // Headset/Bluetooth receiver
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "Audio becoming noisy - pausing playback")
                playbackController.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService created")

        initializeComponents()
        createNotificationChannel()
        registerReceivers()
        // WakeLock is NOT acquired here; it is acquired only during active playback.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_PLAY -> playbackController.play()
            ACTION_PAUSE -> playbackController.pause()
            ACTION_STOP -> {
                playbackController.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_NEXT -> playbackController.next()
            ACTION_PREVIOUS -> playbackController.previous()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return if (SERVICE_INTERFACE == intent.action) {
            super.onBind(intent)!!
        } else {
            binder
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: android.os.Bundle?
    ): BrowserRoot {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        Log.i(TAG, "PlaybackService destroyed")
        releaseAudioFocus()
        unregisterReceivers()
        releaseWakeLock()
        mediaSessionManager.release()
        playbackController.release()
        engine.shutdown()
        usbAudioManager.closeDevice()
        usbAudioManager.unregisterReceiver()
        super.onDestroy()
    }

    // --- Audio Focus ---

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                if (hadAudioFocusBeforeTransientLoss) {
                    playbackController.play()
                    hadAudioFocusBeforeTransientLoss = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost permanently")
                hadAudioFocusBeforeTransientLoss = false
                playbackController.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost transiently")
                val currentState = playbackController.state
                hadAudioFocusBeforeTransientLoss = currentState is PlaybackState.Playing
                playbackController.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // BitPerfect mode does not duck - we pause instead
                Log.d(TAG, "Audio focus loss - would duck but pausing for bit-perfect")
                val currentState = playbackController.state
                hadAudioFocusBeforeTransientLoss = currentState is PlaybackState.Playing
                playbackController.pause()
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(this)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .build()

        audioFocusRequest = focusRequest
        val result = audioManager.requestAudioFocus(focusRequest)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
        }
        audioFocusRequest = null
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BitPerfect audio playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun startForegroundPlayback() {
        if (requestAudioFocus()) {
            val notification = notificationManager.buildNotification(
                playbackController.state,
                mediaSessionManager.getSessionToken()
            )
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateNotification() {
        val notification = notificationManager.buildNotification(
            playbackController.state,
            mediaSessionManager.getSessionToken()
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    // --- Initialization ---

    private fun initializeComponents() {
        // Initialize native engine
        engine = NativeAudioEngine()
        engine.initialize()

        // Initialize playback controller
        playbackController = PlaybackController(engine)
        playbackController.addStateListener { state ->
            onPlaybackStateChanged(state)
        }

        // Initialize audio manager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize USB components
        usbAudioManager = UsbAudioManager(this)
        usbPermissionHandler = UsbPermissionHandler(this, usbAudioManager)
        usbErrorRecovery = UsbErrorRecovery(usbAudioManager, playbackController, engine)

        // Initialize media session
        mediaSessionManager = MediaSessionManager(this, playbackController)
        sessionToken = mediaSessionManager.getSessionToken()

        // Initialize notification manager
        notificationManager = PlaybackNotificationManager(this, CHANNEL_ID)

        // Register USB receiver
        usbAudioManager.registerReceiver()
    }

    private fun onPlaybackStateChanged(state: PlaybackState) {
        // Update media session
        mediaSessionManager.updatePlaybackState(state)

        // Update notification
        updateNotification()

        // Handle state-specific actions
        when (state) {
            is PlaybackState.Playing -> {
                acquireWakeLock()
                startForegroundPlayback()
            }
            is PlaybackState.Paused -> {
                releaseWakeLock()
                // Keep foreground notification while paused
            }
            is PlaybackState.Stopped, is PlaybackState.Idle -> {
                releaseWakeLock()
                releaseAudioFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            is PlaybackState.Error -> {
                releaseWakeLock()
                Log.e(TAG, "Playback error: ${state.message}")
                usbErrorRecovery.handlePlaybackError(state.message)
            }
            else -> { /* Loading - keep current wake lock state */ }
        }
    }

    // --- Receivers ---

    private fun registerReceivers() {
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, noisyFilter)
        }
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Noisy receiver not registered")
        }
    }

    // --- Wake Lock ---

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BitPerfect::PlaybackWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    // --- Public API ---

    fun getPlaybackController(): PlaybackController = playbackController
    fun getEngine(): NativeAudioEngine = engine
    fun getUsbManager(): UsbAudioManager = usbAudioManager
    fun getMediaSession(): MediaSessionCompat = mediaSessionManager.getSession()
}
