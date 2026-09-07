package com.bitperfect.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bitperfect.android.BitPerfectApp
import com.bitperfect.android.ServiceLocator
import com.bitperfect.android.engine.DsdManager
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.library.StoragePermissions
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.player.PlaybackStateStore
import com.bitperfect.android.service.PlaybackService
import com.bitperfect.android.ui.diagnostics.DiagnosticsViewModel
import com.bitperfect.android.ui.equalizer.EqualizerViewModel
import com.bitperfect.android.ui.library.LibraryViewModel
import com.bitperfect.android.ui.navigation.BitPerfectNavGraph
import com.bitperfect.android.ui.player.PlayerViewModel
import com.bitperfect.android.ui.settings.SettingsRepository
import com.bitperfect.android.ui.settings.SettingsViewModel
import com.bitperfect.android.ui.theme.BitPerfectTheme
import com.bitperfect.android.ui.theme.ThemeMode
import com.bitperfect.android.usb.UsbAudioManager
import com.bitperfect.android.usb.UsbPermissionHandler
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainActivity - Single activity hosting the Compose navigation.
 *
 * Responsibilities:
 * - Sets up edge-to-edge display
 * - Applies BitPerfect Material 3 theme
 * - Creates and provides ViewModels
 * - Binds to PlaybackService for audio control (deferred until playback starts)
 * - Manages service lifecycle (start/bind/unbind)
 * - Handles system bar insets
 *
 * Note: On Android 16 (API 36), foreground service start is deferred to avoid
 * the app being killed when the service cannot post a notification in time.
 * The service is only started when playback actually begins.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PICKED_AUDIO_DIRECTORY = "picked-audio"
        private val SUPPORTED_FILE_EXTENSIONS = setOf("wav", "wave", "flac")
        private val UNSAFE_FILE_NAME_CHARACTERS = Regex("""[/\\:*?"<>|\x00-\x1F]""")
    }

    private val openAudioDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importAndPlay(uri)
    }

    private val openZipArchive = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // Extraction and library insertion happen in the ViewModel, off the main
        // thread; this only hands over the chosen archive.
        if (uri != null) libraryViewModel?.importZip(uri)
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Read the authoritative state back from the system rather than trusting
        // the result map, which omits permissions that were already granted.
        publishPermissionState()
    }

    private var playbackService: PlaybackService? = null
    private var isBound = false

    /** Guards against restarting the foreground service on every track change. */
    private var hasStartedPlaybackService = false

    // ViewModels (in production, use Hilt/Koin DI)
    // Nullable to prevent UninitializedPropertyAccessException if initializeComponents() fails
    private var playerViewModel: PlayerViewModel? = null
    private var libraryViewModel: LibraryViewModel? = null
    private var settingsViewModel: SettingsViewModel? = null
    private var diagnosticsViewModel: DiagnosticsViewModel? = null
    private var equalizerViewModel: EqualizerViewModel? = null
    private var musicLibrary: MusicLibrary? = null

    // Core components
    private var engine: NativeAudioEngine? = null
    private var dsdManager: DsdManager? = null
    private var usbAudioManager: UsbAudioManager? = null
    private var settingsRepository: SettingsRepository? = null
    private var playbackController: com.bitperfect.android.player.PlaybackController? = null
    private var importGeneration = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.PlaybackBinder
            playbackService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize core components with safety wrapper
        try {
            initializeComponents()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize components: ${e.message}", e)
            // Continue to show UI even if initialization fails
        }

        // NOTE: startPlaybackService() and bindPlaybackService() are intentionally
        // NOT called here. On Android 16+, starting a foreground service in onCreate()
        // causes the app to be killed if the service cannot post a foreground
        // notification quickly enough (e.g., when no DAC is connected).
        // The service should be started only when playback actually begins.

        setContent {
            BitPerfectApp()
        }

        ensureMediaPermissions()
    }

    override fun onStart() {
        super.onStart()
        // The user may have changed permissions in system settings while away.
        publishPermissionState()
    }

    /**
     * Delivered when Android's "choose an app for the USB device" dialog resolves to
     * this app while it is already running. The activity is `singleTop`, so this
     * arrives instead of onCreate and the wiring in initializeComponents does not run
     * again — which is why the grant has to be picked up here.
     *
     * The device itself is not read out of the intent: by the time this arrives it is
     * in `UsbManager.deviceList` with permission attached, which is exactly what
     * [ServiceLocator.UsbControls.reconcile] looks for.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ServiceLocator.usbControls?.reconcile?.invoke()
    }

    override fun onResume() {
        super.onResume()
        // Belt and braces for the same grant. Choosing BitPerfect with "Just once"
        // grants permission without sending any broadcast — the app never asked, so
        // there is nothing to answer — and this is the first moment afterwards that
        // the app is certain to run code. Opening an already-open device is a no-op.
        ServiceLocator.usbControls?.reconcile?.invoke()
    }

    /**
     * Request the audio (and notification) permissions if they are missing.
     */
    private fun ensureMediaPermissions() {
        val missing = StoragePermissions.missingPermissions(this)
        if (missing.isEmpty()) {
            publishPermissionState()
        } else {
            requestPermissions.launch(missing)
        }
    }

    private fun publishPermissionState() {
        val granted = StoragePermissions.hasAudioAccess(this)
        libraryViewModel?.setAudioPermissionGranted(granted)
    }

    /**
     * Get the one USB owner for this engine, building and wiring it on first call.
     *
     * The USB chain was fully built and never once connected. `UsbAudioManager` could
     * claim the streaming interface and hand the file descriptor to the engine, and
     * `UsbPermissionHandler` implemented the whole attach → permission → open →
     * configure sequence. Nothing called any of it: `setListener` had no call sites for
     * either class, `startMonitoring` and `scanForDevices` had none, and the single
     * `registerReceiver()` lived in `PlaybackService` — which is deliberately not
     * started until playback begins, so at the moment a DAC is plugged in and Android
     * launches the app, no receiver existed at all.
     *
     * So `NativeAudioEngine.attachUsbDevice()` was never reached, `isUsbDeviceAttached()`
     * was permanently false, and `PlaybackController.selectSinkForNextTrack()` — whose
     * only condition that is — always chose the Android mixer. "Android output / mixed
     * by Android" was an honest report: the app had simply never been told a DAC was
     * there. Choosing BitPerfect in the system's USB dialog granted permission to a
     * component that was not listening.
     *
     * Owned here because `USB_DEVICE_ATTACHED` in the manifest targets this activity,
     * making it the one component guaranteed to exist when a device arrives. But built
     * against the application context and cached per engine, because this method runs
     * again on every rotation: a second manager would register a second receiver and
     * try to claim an interface the first one is still holding.
     */
    private fun ensureUsbAudioOwner(
        engine: NativeAudioEngine,
        player: PlayerViewModel
    ): UsbAudioManager {
        ServiceLocator.usbAudioManagerFor(engine)?.let { return it }

        val manager = UsbAudioManager(applicationContext, engine)
        // A previous owner can only exist if it was built for a different engine — a
        // PlayerViewModel was cleared and a new engine created. Its claim has to go
        // before this one can succeed.
        ServiceLocator.setUsbAudioOwner(engine, manager)?.let { retired ->
            Log.i(TAG, "Retiring the USB owner of a previous engine")
            retired.closeDevice()
            retired.unregisterReceiver()
        }

        wireUsbAudio(manager, player)
        return manager
    }

    /**
     * Connect a [UsbAudioManager] to the permission sequence and to the UI.
     *
     * Every state change also writes a sentence into [ServiceLocator.usbAttachReport],
     * which the player's audio info panel shows. The snackbar is only visible if the
     * player happens to be open, and a DAC is usually plugged in while looking at the
     * library or at nothing at all — but "why is this not bit-perfect" has to be
     * answerable at any later moment, from a phone, with no log.
     */
    private fun wireUsbAudio(manager: UsbAudioManager, player: PlayerViewModel) {
        val handler = UsbPermissionHandler(applicationContext, manager)
        val controller = player.playbackController

        ServiceLocator.setUsbControls(
            ServiceLocator.UsbControls(
                reconcile = handler::reconcile,
                requestAccess = handler::requestAccessForAttachedDevice
            )
        )

        fun report(sentence: String, alsoShow: Boolean = true) {
            ServiceLocator.usbAttachReport.set(sentence)
            if (alsoShow) player.showExternalMessage(sentence)
        }

        // The permission result arrives as a broadcast, so this receiver has to exist
        // before any request is made. Registering it is all the service ever did.
        manager.registerReceiver()

        manager.setListener(object : UsbAudioManager.UsbAudioListener {
            // Routed into the handler rather than letting it register a second
            // receiver for the same two actions.
            override fun onDeviceAttached(device: UsbDevice) = handler.onDeviceAttached(device)

            override fun onDeviceDetached(device: UsbDevice) {
                // closeDevice() runs inside the manager for the device it opened, so
                // the engine is already detached by the time this is read — which is
                // what lets the move below pick Android's output.
                handler.onDeviceDetached(device)
                report("Disconnected — back to Android output")
                // Otherwise the USB sink keeps feeding an engine with no device and
                // the music simply stops.
                controller.moveCurrentTrackToPreferredOutput()
            }

            // Forwarded so the handler carries on into openAndConfigureDevice.
            // Without this bridge the grant arrived and was dropped.
            override fun onPermissionGranted(device: UsbDevice) =
                handler.onPermissionGranted(device)

            override fun onPermissionDenied(device: UsbDevice) =
                handler.onPermissionDenied(device)

            override fun onDeviceConfigured(
                deviceName: String,
                sampleRates: IntArray,
                bitDepths: IntArray
            ) = Unit

            // The six specific reasons a claim fails — no permission, could not open,
            // unreadable descriptors, not a UAC device, no output endpoint, another
            // driver holding the interface. Every one of them was a log line.
            override fun onError(message: String) {
                report("Attach failed: $message")
            }
        })

        handler.setListener(object : UsbPermissionHandler.PermissionListener {
            override fun onDeviceConnected(device: UsbDevice) {
                report(
                    "${describe(device)} attached — asking Android for access",
                    alsoShow = false
                )
            }

            override fun onDeviceDisconnected(device: UsbDevice) = Unit
            override fun onPermissionGranted(device: UsbDevice) = Unit

            override fun onPermissionPending(device: UsbDevice) {
                // Android's app-chooser is almost certainly on screen. Nothing is
                // wrong yet, so this is recorded without interrupting.
                report(
                    "${describe(device)} attached — choose BitPerfect in Android's " +
                        "USB dialog to allow access",
                    alsoShow = false
                )
            }

            override fun onPermissionDenied(device: UsbDevice) {
                report("Permission refused, so audio stays on Android output")
            }

            override fun onDeviceReady(device: UsbDevice) {
                // The engine holds the claimed descriptor, so move what is already
                // open onto it rather than waiting for the next track. Waiting is what
                // produced "it says the DAC is ready and still plays through Android"
                // — indistinguishable from the DAC never having been claimed.
                val name = describe(device)
                when (controller.moveCurrentTrackToPreferredOutput()) {
                    PlaybackController.OutputMove.SWITCHED ->
                        report("$name — playing bit-perfect through it now")

                    PlaybackController.OutputMove.ALREADY_CORRECT ->
                        report("$name ready — bit-perfect output")

                    PlaybackController.OutputMove.NOTHING_OPEN ->
                        report("$name ready — bit-perfect output")

                    PlaybackController.OutputMove.FORMAT_NOT_SUPPORTED ->
                        report(
                            "$name ready. " +
                                controller.usbBypassReason.orEmpty().ifBlank {
                                    "This track cannot be sent to it untouched."
                                }
                        )
                }
            }

            override fun onDeviceError(device: UsbDevice, error: String) {
                // A DAC is physically present and unusable. This is the case that used
                // to be indistinguishable from having no DAC at all.
                report("${describe(device)} attached but unusable: $error")
            }
        })

        // Covers the DAC that was already plugged in, including the one whose arrival
        // launched this activity through the system's USB dialog.
        handler.scanForDevices()
    }

    /** A DAC's name as a person would say it, falling back to the kernel device path. */
    private fun describe(device: UsbDevice): String {
        val name = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .trim()
        return name.ifBlank { device.deviceName }
    }

    override fun onDestroy() {
        playbackController = null
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun initializeComponents() {
        // Created once in Application.onCreate and shared through the
        // ServiceLocator, so an Activity recreation does not open a second Room
        // instance. The fallback covers a process where onCreate did not run.
        val musicLibrary = ServiceLocator.musicLibrary
            ?: MusicLibrary(applicationContext).also { ServiceLocator.setMusicLibrary(it) }
        this.musicLibrary = musicLibrary

        val playbackFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                    throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
                }

                val createdEngine = NativeAudioEngine()
                try {
                    createdEngine.initialize()
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Native engine initialization failed (link error): ${e.message}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Native engine initialization failed: ${e.message}", e)
                }

                val createdDsdManager = DsdManager()
                val createdController =
                    com.bitperfect.android.player.PlaybackController(createdEngine)
                return PlayerViewModel(
                    createdController,
                    createdEngine,
                    createdDsdManager,
                    musicLibrary,
                    PlaybackStateStore(applicationContext)
                ) as T
            }
        }

        val localPlayerViewModel =
            ViewModelProvider(this, playbackFactory)[PlayerViewModel::class.java]
        val localEngine = localPlayerViewModel.engine
        val localDsdManager = localPlayerViewModel.dsdManager
        playerViewModel = localPlayerViewModel
        engine = localEngine
        dsdManager = localDsdManager
        playbackController = localPlayerViewModel.playbackController

        // Publish the retained engine and controller so any other component
        // reaches these instances rather than constructing its own.
        ServiceLocator.setServiceComponents(
            playbackController = localPlayerViewModel.playbackController,
            engine = localEngine,
            musicLibrary = musicLibrary
        )

        // Promote to a foreground service the moment audio starts.
        //
        // Nothing did this before, which is why there was no notification and no
        // lock-screen controls, and — more importantly — why nothing requested
        // audio focus, so an incoming call never paused the music. The service
        // owns focus, the notification and the media session; it just had to be
        // started. Deferred until playback rather than done in onCreate, because
        // starting a foreground service with no audio to show gets the app killed
        // on Android 14+.
        localPlayerViewModel.playbackController.addStateListener { state ->
            when (state) {
                is PlaybackState.Playing -> ensurePlaybackServiceRunning()
                else -> Unit
            }
        }

        val localUsbAudioManager = ensureUsbAudioOwner(localEngine, localPlayerViewModel)
        usbAudioManager = localUsbAudioManager

        val localSettingsRepository = SettingsRepository(this)
        settingsRepository = localSettingsRepository

        // Put the saved equalizer curve back before anything plays.
        //
        // Restoring used to happen only when the Equalizer screen was opened, so
        // after a restart the effects attached with default settings and the
        // user's curve, bass and treble did nothing until they visited that screen
        // again. The controller keeps the settings and re-applies them to each new
        // AudioTrack session, so this only has to happen once.
        BitPerfectApp.applicationScope.launch {
            val stored = localSettingsRepository.equalizerSettings.first()
            localPlayerViewModel.playbackController.audioEffects?.restoreSettings(stored)
        }



        // Initialize activity-scoped library/settings/diagnostics ViewModels.
        // The library gets the settings repository so the folder-picker choice
        // survives a restart.
        libraryViewModel = LibraryViewModel(musicLibrary, localSettingsRepository)
        settingsViewModel = SettingsViewModel(localSettingsRepository, musicLibrary)

        // Retained through ViewModelProvider so onCleared() actually runs and
        // its PlaybackController listener is removed. Constructing it by hand
        // leaked a listener (and an activity Context) on every recreation.
        val equalizerFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return EqualizerViewModel(
                    localPlayerViewModel.playbackController,
                    SettingsRepository(applicationContext)
                ) as T
            }
        }
        equalizerViewModel =
            ViewModelProvider(this, equalizerFactory)[EqualizerViewModel::class.java]
        diagnosticsViewModel = DiagnosticsViewModel(localEngine, localDsdManager, localUsbAudioManager)
    }

    private fun launchZipPicker() {
        // Some pickers report zips as octet-stream, so accept that too rather
        // than hiding archives the user can see.
        openZipArchive.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    private fun launchAudioPicker() {
        if (!BitPerfectApp.isNativeLoaded) {
            Toast.makeText(
                this,
                "Native audio decoder is unavailable on this device",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        openAudioDocument.launch(
            arrayOf("audio/*", "application/flac", "application/x-flac", "application/octet-stream")
        )
    }

    private fun importAndPlay(uri: Uri) {
        val viewModel = playerViewModel ?: return
        val requestGeneration = ++importGeneration
        lifecycleScope.launch {
            var importedFile: File? = null
            try {
                val copiedFile = withContext(Dispatchers.IO) {
                    copyAudioDocumentToCache(uri)
                }
                importedFile = copiedFile
                if (requestGeneration != importGeneration) {
                    withContext(Dispatchers.IO) { copiedFile.delete() }
                    return@launch
                }

                viewModel.playFile(copiedFile.absolutePath)
                withContext(Dispatchers.IO) {
                    cleanupPickedAudioCache(copiedFile)
                }
            } catch (cancelled: CancellationException) {
                importedFile?.delete()
                throw cancelled
            } catch (error: Exception) {
                if (requestGeneration == importGeneration) {
                    Log.e(TAG, "Could not import selected audio file", error)
                    Toast.makeText(
                        this@MainActivity,
                        "Could not open that audio file",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    importedFile?.delete()
                }
            }
        }
    }

    private fun copyAudioDocumentToCache(uri: Uri): File {
        val displayName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()

        val extension = displayName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in SUPPORTED_FILE_EXTENSIONS }
            ?: "audio"

        // Preserve the original file name so the player shows the real title
        // rather than an internal cache name.
        val baseName = displayName
            .substringAfterLast('/')
            .substringBeforeLast('.', displayName.substringAfterLast('/'))
            .replace(UNSAFE_FILE_NAME_CHARACTERS, "_")
            .trim()
            .take(120)
            .ifBlank { "track" }

        val cacheDirectory = File(cacheDir, PICKED_AUDIO_DIRECTORY)
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Could not create audio cache directory")
        }

        // Each import gets its own subdirectory, so the visible file name can
        // match the source document without colliding with other imports.
        val importDirectory = File(cacheDirectory, System.currentTimeMillis().toString())
        if (!importDirectory.exists() && !importDirectory.mkdirs()) {
            throw IOException("Could not create audio import directory")
        }

        val target = File(importDirectory, "$baseName.$extension")
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("Content provider returned no input stream")
            input.buffered().use { source ->
                target.outputStream().buffered().use { destination ->
                    source.copyTo(destination)
                }
            }
            if (target.length() == 0L) throw IOException("Selected file is empty")
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun cleanupPickedAudioCache(currentFile: File) {
        val currentImportDirectory = currentFile.parentFile ?: return
        val cacheRoot = currentImportDirectory.parentFile ?: return

        cacheRoot.listFiles()?.forEach { entry ->
            if (entry != currentImportDirectory) entry.deleteRecursively()
        }
    }

    /**
     * Start the PlaybackService as a foreground service.
     * Should only be called when playback is about to begin, not during onCreate().
     */
    fun startPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        startForegroundService(intent)
    }

    /**
     * Start and bind the playback service once, on first playback.
     *
     * Idempotent: called from every Playing state change, which happens on every
     * track, so it must not restart the service each time.
     */
    private fun ensurePlaybackServiceRunning() {
        if (hasStartedPlaybackService) return
        hasStartedPlaybackService = true
        try {
            startPlaybackService()
            bindPlaybackService()
        } catch (error: Exception) {
            // A failure here costs the notification, not playback, so it must not
            // take the app down with it.
            hasStartedPlaybackService = false
            Log.e(TAG, "Could not start the playback service: ${error.message}", error)
        }
    }

    /**
     * Bind to the PlaybackService for direct communication.
     * Should only be called after startPlaybackService().
     */
    fun bindPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @Composable
    private fun BitPerfectApp() {
        // Follow the saved preference. This was pinned to SYSTEM, so the Theme
        // setting in Settings wrote a value that nothing read and the app could
        // not be forced to light or dark.
        val settingsState = settingsViewModel?.uiState?.collectAsState()
        val themeMode = when (settingsState?.value?.themeMode) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }

        BitPerfectTheme(
            themeMode = themeMode,
            dynamicColor = true
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val pvm = playerViewModel
                val lvm = libraryViewModel
                val svm = settingsViewModel
                val dvm = diagnosticsViewModel
                val evm = equalizerViewModel
                val library = musicLibrary

                if (pvm != null && lvm != null && svm != null && dvm != null &&
                    evm != null && library != null
                ) {
                    BitPerfectNavGraph(
                        playerViewModel = pvm,
                        libraryViewModel = lvm,
                        settingsViewModel = svm,
                        diagnosticsViewModel = dvm,
                        equalizerViewModel = evm,
                        musicLibrary = library,
                        onOpenFile = ::launchAudioPicker,
                        onPickZip = ::launchZipPicker
                    )
                } else {
                    // Show a safe fallback screen when ViewModels failed to initialize
                    InitializationErrorScreen()
                }
            }
        }
    }

    @Composable
    private fun InitializationErrorScreen() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "BitPerfect",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Initialization failed. Please restart the app.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
