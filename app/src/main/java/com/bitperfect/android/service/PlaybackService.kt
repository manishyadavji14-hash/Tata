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
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.session.MediaSession
import com.bitperfect.android.ServiceLocator
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.library.MusicLibrary
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
    // Nullable rather than lateinit: if the media session or notification cannot
    // be built, playback must still work. Previously a failure here threw out of
    // onCreate and killed the whole app.
    private var mediaSessionManager: MediaSessionManager? = null
    private var notificationManager: PlaybackNotificationManager? = null
    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var usbPermissionHandler: UsbPermissionHandler
    private lateinit var usbErrorRecovery: UsbErrorRecovery

    // Audio focus
    private lateinit var audioManager: AudioManager

    /**
     * False when the engine and controller came from ServiceLocator, in which
     * case the Activity's retained ViewModel owns them and this service must not
     * release them.
     */
    private var ownsEngineAndController = false

    /**
     * True once initializeComponents has completed. The lateinit fields above are
     * only safe to touch when this is set, so every entry point the system can
     * call has to check it rather than assume construction succeeded.
     */
    private var componentsReady = false

    /** Retained so onDestroy can detach it from a shared controller. */
    private var playbackStateListener: (PlaybackState) -> Unit = {}
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
                if (componentsReady) playbackController.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "PlaybackService created")

        // IMPORTANT: Create notification channel FIRST, before any other initialization.
        // On Android 16 (API 36), the system requires the foreground notification to be
        // posted very quickly after service start, so the channel must exist immediately.
        createNotificationChannel()

        // This service is optional infrastructure: it supplies the notification,
        // lock-screen controls and audio focus, but audio plays without it. An
        // exception thrown out of onCreate becomes "Unable to create service" and
        // kills the whole app — and because onStartCommand returns START_STICKY,
        // the system relaunches it straight into the same crash, so the app dies
        // in a loop. Failing soft here is the difference between losing the
        // notification and losing the app.
        try {
            initializeComponents()
            componentsReady = true
        } catch (error: Exception) {
            Log.e(TAG, "Service initialization failed; running degraded", error)
        } catch (error: LinkageError) {
            // A missing or mismatched native/library symbol surfaces as an Error
            // rather than an Exception, and is just as fatal to onCreate.
            Log.e(TAG, "Service initialization failed to link; running degraded", error)
        }
        registerReceivers()
        // WakeLock is NOT acquired here; it is acquired only during active playback.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        // Immediately call startForeground with a basic notification so Android
        // does not kill the service. On Android 16+, the system enforces strict
        // timing for foreground service notifications.
        try {
            val notification = buildBasicNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}", e)
        }

        // Notification actions arrive here. Without initialized components there
        // is no controller to act on, and reaching for the lateinit fields would
        // crash the process a second time.
        if (componentsReady) {
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
        } else {
            Log.w(TAG, "Ignoring ${intent?.action}: service is running degraded")
        }

        // NOT sticky: a service that failed to initialize would otherwise be
        // relaunched by the system straight back into the same failure. Playback
        // is driven by the app, which restarts this service when it next plays.
        return START_NOT_STICKY
    }

    /**
     * Builds a minimal notification for immediate foreground promotion.
     * This ensures the service is not killed by the system before the full
     * media notification can be built.
     */
    private fun buildBasicNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BitPerfect")
            .setContentText("Audio service running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
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
        mediaSessionManager?.release()

        if (!componentsReady) {
            // Nothing below was successfully constructed.
            super.onDestroy()
            return
        }


        // Always detach the listener, or this service leaks through a controller
        // that outlives it.
        playbackController.removeStateListener(playbackStateListener)

        // Only tear down what this service created. Releasing the shared
        // controller would stop the audio the UI is still driving, and shutting
        // down the shared engine would close decoders out from under it.
        if (ownsEngineAndController) {
            playbackController.release()
            engine.shutdown()
        }

        usbAudioManager.closeDevice()
        usbAudioManager.unregisterReceiver()
        super.onDestroy()
    }

    // --- Audio Focus ---

    override fun onAudioFocusChange(focusChange: Int) {
        if (!componentsReady) return
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
        // Focus first: without it another app owns the output and starting to
        // play would talk over it.
        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus denied; not promoting to foreground")
            return
        }
        // Fall back to the basic notification if the rich one is unavailable, so
        // the service still has something to be foreground with.
        val notification = buildPlaybackNotification() ?: buildBasicNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    fun updateNotification() {
        val notification = buildPlaybackNotification() ?: return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * The transport-controls notification, or null when either the notification
     * builder or the media session could not be created.
     */
    private fun buildPlaybackNotification(): Notification? {
        val manager = notificationManager ?: return null
        val token = mediaSessionManager?.getSessionToken() ?: return null
        return try {
            manager.buildNotification(playbackController.state, token)
        } catch (error: Exception) {
            Log.e(TAG, "Could not build the playback notification: ${error.message}", error)
            null
        }
    }

    // --- Initialization ---

    private fun initializeComponents() {
        // Adopt the engine and controller the UI is actually playing through.
        //
        // This service used to construct its own pair, which meant the
        // notification and the media session controlled a second, silent
        // controller while audio came from the Activity's. Taking the shared
        // instances is what makes the notification, lock-screen controls and
        // audio focus act on the audio you can hear.
        val sharedEngine = ServiceLocator.engine
        val sharedController = ServiceLocator.playbackController

        if (sharedEngine != null && sharedController != null) {
            engine = sharedEngine
            playbackController = sharedController
            ownsEngineAndController = false
            Log.i(TAG, "Using shared engine and controller from ServiceLocator")
        } else {
            // The service can be recreated by the system with no Activity alive,
            // in which case there is nothing to share and it owns its own.
            engine = NativeAudioEngine()
            engine.initialize()
            playbackController = PlaybackController(engine)
            ownsEngineAndController = true
            ServiceLocator.setServiceComponents(
                playbackController = playbackController,
                engine = engine,
                musicLibrary = ServiceLocator.musicLibrary ?: MusicLibrary(applicationContext)
            )
            Log.i(TAG, "No shared components; service created its own")
        }

        // Held so it can be removed in onDestroy. Adding an anonymous lambda to
        // a controller the service does not own would leak this service.
        playbackStateListener = { state -> onPlaybackStateChanged(state) }
        playbackController.addStateListener(playbackStateListener)

        // Register track transition callback so the native gapless engine
        // can notify the controller when a track finishes playing
        engine.registerTrackTransitionCallback(playbackController)

        // Initialize audio manager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Initialize USB components
        usbAudioManager = UsbAudioManager(this, engine)
        usbPermissionHandler = UsbPermissionHandler(this, usbAudioManager)
        usbErrorRecovery = UsbErrorRecovery(usbAudioManager, playbackController, engine)

        // Initialize media session
        // Best-effort: a media session is what gives lock-screen and headset
        // controls, but losing it must not cost the user playback.
        try {
            val manager = MediaSessionManager(this, playbackController)
            mediaSessionManager = manager
            sessionToken = manager.getSessionToken()
        } catch (error: Exception) {
            Log.e(TAG, "Media session unavailable: ${error.message}", error)
        }

        // Initialize notification manager
        notificationManager = try {
            PlaybackNotificationManager(this, CHANNEL_ID)
        } catch (error: Exception) {
            Log.e(TAG, "Notification manager unavailable: ${error.message}", error)
            null
        }

        // Register USB receiver
        usbAudioManager.registerReceiver()
    }

    private fun onPlaybackStateChanged(state: PlaybackState) {
        // Update media session
        mediaSessionManager?.updatePlaybackState(state)

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
    fun getMediaSession(): MediaSession? = mediaSessionManager?.getSession()
}
