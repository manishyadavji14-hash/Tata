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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        /**
         * How many times a track's cover is re-read before giving up.
         *
         * Retrying at all is the point — one transient failure used to mean no
         * cover for the whole track. Bounding it is what stops a seek storm from
         * re-reading tags on every state change.
         */
        private const val MAX_ARTWORK_ATTEMPTS = 3

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

    /**
     * Scope for resolving track metadata.
     *
     * The service needs one of its own: reading tags and decoding a cover is disk
     * work that must not run on the callback thread that delivers playback state,
     * and it has to keep running while the app has no Activity.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val artworkLoader by lazy { ArtworkLoader(this) }

    /** Track whose metadata is currently published, to avoid redundant lookups. */
    private var publishedMetadataPath: String? = null
    private var metadataJob: Job? = null

    /**
     * Whether the current track's cover reached the media session.
     *
     * Reported to the UI because the lock screen is the one surface whose failures
     * are invisible from inside the app, and the maintainer has no way to read a
     * log. "The app shows a cover but the lock screen does not" was diagnosed three
     * times over from guesses; this makes it answerable from the device.
     */
    private var artworkPublishState: ArtworkPublishState = ArtworkPublishState.None
        set(value) {
            field = value
            ServiceLocator.artworkPublishReport.set(value.describe())
        }

    /** Attempts made for [publishedMetadataPath], to bound retrying. */
    private var artworkAttempts = 0

    private sealed interface ArtworkPublishState {
        /** Nothing recorded for this track, so nothing to publish. */
        data object None : ArtworkPublishState

        /** A cover is recorded but has not been published yet. */
        data object Pending : ArtworkPublishState

        /** Published, carrying [bytes] of cover data. */
        data class Published(val bytes: Int) : ArtworkPublishState

        fun describe(): String = when (this) {
            is None -> "No cover recorded for this track"
            is Pending -> "Cover recorded but not published — retrying"
            is Published -> if (bytes > 0) {
                "Published to the media session (${bytes / 1024} KB)"
            } else {
                // A bitmap for the notification but no bytes for the session: the
                // notification will show a cover and the lock screen will not.
                "Published to the notification only, without session bytes"
            }
        }
    }
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

        // Immediately call startForeground so Android does not kill the service.
        // On Android 16+, the system enforces strict timing for foreground service
        // notifications.
        //
        // Prefer the real media notification. Every transport tap in the shade
        // arrives here as a start command, and unconditionally posting the basic
        // notification replaced the media one — same id — so the panel lost its
        // artwork and title until the next state change happened to rebuild it.
        // The basic notification stays as the fallback for a degraded service,
        // which is the case it was added for.
        try {
            val notification = buildPlaybackNotification() ?: buildBasicNotification()
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
        // Stop any in-flight metadata lookup before the session it would write to
        // is released.
        serviceScope.cancel()
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
     * The transport-controls notification, or null only when the notification
     * builder itself could not be created.
     */
    private fun buildPlaybackNotification(): Notification? {
        val manager = notificationManager ?: return null
        // The session token links the notification to the media session, and is
        // what lets the system draw a scrubber. It is **not** required to draw a
        // notification at all. Returning null when it was missing threw away the
        // entire music notification — title, cover, transport controls — leaving
        // only the bare fallback, which shows nothing media-like on a lock screen
        // and gives the user nothing to explain it. A notification without a token
        // is worth vastly more than no notification.
        val token = mediaSessionManager?.getSessionToken()
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

        // Publish whatever is already playing.
        //
        // The service is started when playback begins, so by the time the state
        // listener above is attached the controller may already be in Playing and
        // no further transition is coming. Without this the notification would sit
        // with no title, artist or artwork until the user happened to pause or
        // change track.
        //
        // Deliberately not the whole onPlaybackStateChanged: that promotes the
        // service to the foreground, and startForeground() from onCreate is
        // rejected when the service was only bound — which the catch around
        // initializeComponents would read as a failed init and disable the
        // notification entirely.
        val current = playbackController.state
        publishMetadataFor(current)
        mediaSessionManager?.updatePlaybackState(current)
    }

    private fun onPlaybackStateChanged(state: PlaybackState) {
        // Publish what is playing before the state, so the system has a title and
        // a duration to draw as soon as the transport controls appear.
        publishMetadataFor(state)

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

    /**
     * Resolve and publish the current track's title, artist, album, duration and
     * cover to the media session and the notification.
     *
     * This is what the lock screen, the notification shade and vendor media
     * widgets read. Nothing used to call it, which is why they all showed
     * "Unknown song" with no artwork and no track length for the entire life of
     * the process.
     *
     * Keyed on the path so a pause or a seek does not repeat the disk work; the
     * lookup reads tags and decodes a cover, which is far too expensive to do on
     * every state change.
     */
    private fun publishMetadataFor(state: PlaybackState) {
        val trackPath = when (state) {
            is PlaybackState.Loading -> state.trackPath
            is PlaybackState.Playing -> state.trackPath
            is PlaybackState.Paused -> state.trackPath
            // Nothing is playing, so leave the last metadata in place rather than
            // blanking the panel while it is still on screen.
            else -> return
        }

        if (trackPath.isBlank()) return

        if (trackPath == publishedMetadataPath) {
            // Already done with this track, unless the cover is still outstanding.
            if (artworkPublishState != ArtworkPublishState.Pending) return
            if (artworkAttempts >= MAX_ARTWORK_ATTEMPTS) return
        } else {
            publishedMetadataPath = trackPath
            artworkPublishState = ArtworkPublishState.Pending
            artworkAttempts = 0
        }

        artworkAttempts++
        metadataJob?.cancel()
        metadataJob = serviceScope.launch {
            val info = withContext(Dispatchers.IO) { loadTrackInfo(trackPath) }

            // The track may have moved on while the tags were being read; a late
            // result must not overwrite the newer one.
            if (publishedMetadataPath != trackPath) return@launch

            // Settle the cover before publishing, so a retry is driven by what
            // actually arrived rather than by how far the code got.
            //
            // The latch used to be the path alone, set *before* this work and never
            // cleared. One failed cover — the library not yet reachable, a decode
            // that returned nothing, bytes that would not fit — meant every later
            // state change for that track returned early and no cover was ever
            // published again for as long as it played. "Not yet" and "there is
            // none" have to be different states, or the first is permanent.
            artworkPublishState = when {
                info.artwork.bitmap != null || info.artwork.data != null ->
                    ArtworkPublishState.Published(info.artwork.data?.size ?: 0)
                // Nothing was recorded for this track, so there is nothing to wait
                // for and retrying would only re-read tags on every pause.
                !info.hasArtworkReference -> ArtworkPublishState.None
                else -> ArtworkPublishState.Pending
            }

            mediaSessionManager?.updateMetadata(
                trackPath = trackPath,
                title = info.title,
                artist = info.artist,
                album = info.album,
                durationMs = info.durationMs,
                artworkUri = info.artwork.uri,
                artworkData = info.artwork.data
            )
            notificationManager?.updateTrackInfo(
                title = info.title,
                artist = info.artist,
                album = info.album,
                artwork = info.artwork.bitmap
            )
            updateNotification()

            Log.d(TAG, "Cover for $trackPath: $artworkPublishState (attempt $artworkAttempts)")
        }
    }

    private data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val artwork: ArtworkLoader.Loaded,
        /**
         * Whether the library had a cover recorded at all.
         *
         * Separate from whether one was loaded: it is the difference between "this
         * track has no cover" and "this track has a cover that did not load", and
         * only the second is worth retrying.
         */
        val hasArtworkReference: Boolean
    )

    /**
     * Read a track's details, falling back to the file name.
     *
     * A file played straight from the picker has no library row, and the library
     * may not be reachable at all if the process was rebuilt for the service
     * alone. Showing the file name beats showing nothing.
     */
    private suspend fun loadTrackInfo(trackPath: String): TrackInfo {
        val fallbackTitle = trackPath.substringAfterLast('/').substringBeforeLast('.')

        val details = try {
            ServiceLocator.musicLibrary?.getTrackDetails(trackPath)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read details for $trackPath: ${error.message}")
            null
        }

        if (details == null) {
            return TrackInfo(
                title = fallbackTitle,
                artist = "",
                album = "",
                durationMs = 0L,
                artwork = ArtworkLoader.Loaded(),
                // The library was unreachable, not consulted — so this is "not yet"
                // rather than "there is none", and it is worth asking again.
                hasArtworkReference = true
            )
        }

        return TrackInfo(
            title = details.title.ifBlank { fallbackTitle },
            artist = details.artist,
            album = details.album,
            durationMs = details.durationMs,
            artwork = artworkLoader.load(details.artworkUri),
            hasArtworkReference = !details.artworkUri.isNullOrBlank()
        )
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
