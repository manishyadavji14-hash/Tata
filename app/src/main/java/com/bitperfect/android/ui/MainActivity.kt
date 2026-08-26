package com.bitperfect.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitperfect.android.BitPerfectApp
import com.bitperfect.android.engine.DsdManager
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.library.MusicLibrary
import com.bitperfect.android.service.PlaybackService
import com.bitperfect.android.ui.diagnostics.DiagnosticsViewModel
import com.bitperfect.android.ui.library.LibraryViewModel
import com.bitperfect.android.ui.navigation.BitPerfectNavGraph
import com.bitperfect.android.ui.player.PlayerViewModel
import com.bitperfect.android.ui.settings.SettingsRepository
import com.bitperfect.android.ui.settings.SettingsViewModel
import com.bitperfect.android.ui.theme.BitPerfectTheme
import com.bitperfect.android.ui.theme.ThemeMode
import com.bitperfect.android.usb.UsbAudioManager

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
    }

    private var playbackService: PlaybackService? = null
    private var isBound = false

    // ViewModels (in production, use Hilt/Koin DI)
    // Nullable to prevent UninitializedPropertyAccessException if initializeComponents() fails
    private var playerViewModel: PlayerViewModel? = null
    private var libraryViewModel: LibraryViewModel? = null
    private var settingsViewModel: SettingsViewModel? = null
    private var diagnosticsViewModel: DiagnosticsViewModel? = null

    // Core components
    private var engine: NativeAudioEngine? = null
    private var dsdManager: DsdManager? = null
    private var usbAudioManager: UsbAudioManager? = null
    private var settingsRepository: SettingsRepository? = null

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
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun initializeComponents() {
        // Initialize engine and managers
        val localEngine = NativeAudioEngine()
        engine = localEngine

        // Wrap engine.initialize() specifically - JNI may fail if native library
        // did not load or if the device lacks required capabilities
        try {
            localEngine.initialize()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native engine initialization failed (link error): ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Native engine initialization failed: ${e.message}", e)
        }

        // DsdManager methods are safe (no external/JNI calls), but wrap creation
        // in try-catch for any future issues
        val localDsdManager = try {
            DsdManager()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "DsdManager creation failed (link error): ${e.message}", e)
            DsdManager() // DsdManager no longer has external funs, so this is safe
        } catch (e: Exception) {
            Log.e(TAG, "DsdManager creation failed: ${e.message}", e)
            DsdManager()
        }
        dsdManager = localDsdManager

        val localUsbAudioManager = UsbAudioManager(this)
        usbAudioManager = localUsbAudioManager

        val localSettingsRepository = SettingsRepository(this)
        settingsRepository = localSettingsRepository

        // Create music library
        val musicLibrary = MusicLibrary()

        // Initialize ViewModels
        val playbackController = com.bitperfect.android.player.PlaybackController(localEngine)

        playerViewModel = PlayerViewModel(playbackController, localEngine, localDsdManager)
        libraryViewModel = LibraryViewModel(musicLibrary)
        settingsViewModel = SettingsViewModel(localSettingsRepository)
        diagnosticsViewModel = DiagnosticsViewModel(localEngine, localDsdManager, localUsbAudioManager)
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
        BitPerfectTheme(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = true
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val pvm = playerViewModel
                val lvm = libraryViewModel
                val svm = settingsViewModel
                val dvm = diagnosticsViewModel

                if (pvm != null && lvm != null && svm != null && dvm != null) {
                    BitPerfectNavGraph(
                        playerViewModel = pvm,
                        libraryViewModel = lvm,
                        settingsViewModel = svm,
                        diagnosticsViewModel = dvm
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
