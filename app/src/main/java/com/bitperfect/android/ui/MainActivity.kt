package com.bitperfect.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
                    musicLibrary
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

        val localUsbAudioManager = UsbAudioManager(this, localEngine)
        usbAudioManager = localUsbAudioManager

        val localSettingsRepository = SettingsRepository(this)
        settingsRepository = localSettingsRepository



        // Initialize activity-scoped library/settings/diagnostics ViewModels.
        // The library gets the settings repository so the folder-picker choice
        // survives a restart.
        libraryViewModel = LibraryViewModel(musicLibrary, localSettingsRepository)
        settingsViewModel = SettingsViewModel(localSettingsRepository)

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
