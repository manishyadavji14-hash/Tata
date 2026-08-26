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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitperfect.android.ServiceLocator
import com.bitperfect.android.engine.DsdManager
import com.bitperfect.android.service.PlaybackService
import com.bitperfect.android.ui.diagnostics.DiagnosticsViewModel
import com.bitperfect.android.ui.library.LibraryViewModel
import com.bitperfect.android.ui.navigation.BitPerfectNavGraph
import com.bitperfect.android.ui.player.PlayerViewModel
import com.bitperfect.android.ui.queue.QueueViewModel
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
 * - Starts and binds to PlaybackService (the single owner of engine + controller)
 * - Creates ViewModels AFTER the service is bound and ServiceLocator is populated
 * - Manages service lifecycle (start/bind/unbind)
 * - Handles system bar insets
 *
 * Architecture:
 * PlaybackService owns the single NativeAudioEngine and PlaybackController.
 * When this Activity binds to the service, it populates ServiceLocator so that
 * ViewModels can access shared instances without passing service references
 * through deep Compose trees.
 *
 * Note: On Android 16 (API 36), foreground service start requires the notification
 * channel to exist and the service to call startForeground() quickly. The service
 * is started here in onCreate so it is ready when playback begins, but actual
 * foreground promotion happens inside the service's onStartCommand.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var playbackService: PlaybackService? = null
    private var isBound = false

    // Reactive state for Compose: true once service is bound and ViewModels are ready
    private val isServiceReady = mutableStateOf(false)

    // ViewModels - created only after service binding completes
    private var playerViewModel: PlayerViewModel? = null
    private var libraryViewModel: LibraryViewModel? = null
    private var settingsViewModel: SettingsViewModel? = null
    private var diagnosticsViewModel: DiagnosticsViewModel? = null
    private var queueViewModel: QueueViewModel? = null

    // Settings repository (Activity-scoped, needs Context)
    private var settingsRepository: SettingsRepository? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.PlaybackBinder
            playbackService = binder.getService()
            isBound = true

            // Populate ServiceLocator with the service's single engine and controller
            ServiceLocator.setServiceComponents(
                playbackController = binder.getPlaybackController(),
                engine = binder.getEngine(),
                musicLibrary = ServiceLocator.musicLibrary!!
            )

            Log.i(TAG, "Bound to PlaybackService - ServiceLocator populated")

            // Now create ViewModels with the shared instances
            try {
                initializeViewModels()
                isServiceReady.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ViewModels: ${e.message}", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
            isServiceReady.value = false
            ServiceLocator.clearServiceReferences()
            Log.w(TAG, "Disconnected from PlaybackService")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the PlaybackService. It will call startForeground() in onStartCommand.
        // This ensures the single engine + controller are created and available for binding.
        startPlaybackService()

        // Bind to the service to get access to its engine and controller.
        bindPlaybackService()

        setContent {
            BitPerfectApp()
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            ServiceLocator.clearServiceReferences()
        }
        super.onDestroy()
    }

    /**
     * Initialize ViewModels using shared instances from ServiceLocator.
     * Called only after the service is bound and ServiceLocator is populated.
     */
    private fun initializeViewModels() {
        val controller = ServiceLocator.playbackController
            ?: throw IllegalStateException("PlaybackController not available in ServiceLocator")
        val engine = ServiceLocator.engine
            ?: throw IllegalStateException("NativeAudioEngine not available in ServiceLocator")
        val musicLibrary = ServiceLocator.musicLibrary
            ?: throw IllegalStateException("MusicLibrary not available in ServiceLocator")

        // DsdManager is a pure-Kotlin helper (no JNI) - safe to create here
        val dsdManager = DsdManager()

        val localSettingsRepository = SettingsRepository(this)
        settingsRepository = localSettingsRepository

        // Get UsbAudioManager from service for diagnostics
        val usbManager = playbackService?.getUsbManager()

        playerViewModel = PlayerViewModel(controller, engine, dsdManager)
        libraryViewModel = LibraryViewModel(musicLibrary, localSettingsRepository)
        settingsViewModel = SettingsViewModel(localSettingsRepository)
        queueViewModel = QueueViewModel(controller)
        diagnosticsViewModel = if (usbManager != null) {
            DiagnosticsViewModel(engine, dsdManager, usbManager)
        } else {
            // Fallback: create UsbAudioManager with the service's engine
            DiagnosticsViewModel(engine, dsdManager, UsbAudioManager(this, engine))
        }
    }

    /**
     * Start the PlaybackService as a foreground service.
     */
    private fun startPlaybackService() {
        try {
            val intent = Intent(this, PlaybackService::class.java)
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PlaybackService: ${e.message}", e)
        }
    }

    /**
     * Bind to the PlaybackService for direct communication.
     */
    private fun bindPlaybackService() {
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
                if (isServiceReady.value) {
                    val pvm = playerViewModel
                    val lvm = libraryViewModel
                    val svm = settingsViewModel
                    val dvm = diagnosticsViewModel
                    val qvm = queueViewModel

                    if (pvm != null && lvm != null && svm != null && dvm != null && qvm != null) {
                        BitPerfectNavGraph(
                            playerViewModel = pvm,
                            libraryViewModel = lvm,
                            settingsViewModel = svm,
                            diagnosticsViewModel = dvm,
                            queueViewModel = qvm
                        )
                    } else {
                        InitializationErrorScreen()
                    }
                } else {
                    // Show loading while waiting for service to bind
                    ServiceBindingScreen()
                }
            }
        }
    }

    @Composable
    private fun ServiceBindingScreen() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "BitPerfect",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Initializing audio engine...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
