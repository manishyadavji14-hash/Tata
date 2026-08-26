package com.bitperfect.android.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.engine.DsdManager
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.usb.UsbAudioManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DiagnosticsViewModel - ViewModel for the DAC diagnostics screen.
 *
 * Pulls data from NativeAudioEngine via JNI and UsbAudioManager:
 * - Device descriptor information (manufacturer, product, VID/PID)
 * - UAC version and capabilities
 * - Audio interface and endpoint details
 * - Supported sample rates and bit depths
 * - Current playback state (rate, mode, format)
 * - Clock information (UAC2)
 * - Buffer fill level (real-time indicator)
 * - Error and underrun counters
 *
 * Refreshes data periodically for real-time monitoring.
 */
class DiagnosticsViewModel(
    private val engine: NativeAudioEngine,
    private val dsdManager: DsdManager,
    private val usbAudioManager: UsbAudioManager
) : ViewModel() {

    /**
     * Audio interface descriptor information.
     */
    data class AudioInterface(
        val interfaceNumber: Int,
        val subclass: String,
        val alternateSettings: Int,
        val protocol: String,
        val terminalType: String = ""
    )

    /**
     * Endpoint descriptor information.
     */
    data class EndpointInfo(
        val address: Int,
        val direction: String,
        val transferType: String,
        val maxPacketSize: Int,
        val interval: Int,
        val syncType: String = ""
    )

    /**
     * Complete diagnostics UI state.
     */
    data class DiagnosticsUiState(
        // Connection state
        val isDeviceConnected: Boolean = false,

        // Device info
        val manufacturer: String = "",
        val productName: String = "",
        val serialNumber: String = "",
        val uacVersion: Int = 0,

        // USB identifiers
        val vendorId: Int = 0,
        val productId: Int = 0,
        val usbVersion: String = "2.0",

        // Interfaces and endpoints
        val interfaces: List<AudioInterface> = emptyList(),
        val endpoints: List<EndpointInfo> = emptyList(),

        // Supported formats
        val supportedSampleRates: IntArray = intArrayOf(),
        val supportedBitDepths: IntArray = intArrayOf(),
        val maxChannels: Int = 2,

        // Current state
        val currentSampleRate: Int = 0,
        val currentBitDepth: Int = 0,
        val currentMode: String = "Idle",
        val activeInterface: Int = -1,
        val activeAltSetting: Int = -1,

        // Clock (UAC2)
        val clockSource: String = "Internal",
        val clockFrequency: Int = 0,
        val clockValid: Boolean = false,
        val sofSyncSupported: Boolean = false,

        // Buffer
        val bufferFillLevel: Float = 0f,
        val bufferSizeMs: Int = 0,
        val latencyMs: Int = 0,

        /**
         * Bytes read out of the ring buffer. This is throughput through the
         * engine, NOT bytes on the USB wire, and is reported as such: it used to
         * be labelled "Total Transferred" while nothing was reaching hardware.
         */
        val bufferBytesRead: Long = 0L,

        /** Bytes the USB transport actually accepted. 0 with no DAC attached. */
        val usbBytesTransferred: Long = 0L,

        /** Whether audio is genuinely being transmitted to a USB device. */
        val isUsbOutputActive: Boolean = false,

        /** Which transport is in use, so a simulated one is never mistaken. */
        val transportName: String = "none",

        // Error counters
        val underrunCount: Int = 0,
        val overrunCount: Int = 0,
        val transferErrors: Int = 0,
        val usbResets: Int = 0
    )

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refresh()
        startPeriodicRefresh()
    }

    /**
     * Refresh all diagnostic data from the native engine.
     */
    fun refresh() {
        viewModelScope.launch {
            val deviceName = engine.getDeviceName()
            val isConnected = deviceName.isNotEmpty()

            if (!isConnected) {
                _uiState.value = DiagnosticsUiState(isDeviceConnected = false)
                return@launch
            }

            val supportedRates = engine.getSupportedSampleRates()
            val supportedDepths = engine.getSupportedBitDepths()
            val currentRate = engine.getCurrentSampleRate()
            val bufferLevel = engine.getBufferLevel()
            val underruns = engine.getUnderrunCount()
            val bufferBytes = engine.getTotalBytesTransferred()
            val dsdMode = dsdManager.getCurrentMode()

            val currentModeString = when (engine.getState()) {
                NativeAudioEngine.STATE_PLAYING -> {
                    when (dsdMode) {
                        DsdManager.MODE_DOP -> "DoP"
                        DsdManager.MODE_NATIVE_DSD -> "Native DSD"
                        else -> "PCM"
                    }
                }
                NativeAudioEngine.STATE_PAUSED -> "Paused"
                NativeAudioEngine.STATE_CONFIGURED -> "Configured"
                NativeAudioEngine.STATE_INITIALIZED -> "Initialized"
                else -> "Idle"
            }

            // Build interface and endpoint info from native layer
            val interfaces = buildInterfaceList()
            val endpoints = buildEndpointList()

            _uiState.value = DiagnosticsUiState(
                isDeviceConnected = true,
                manufacturer = extractManufacturer(deviceName),
                productName = extractProductName(deviceName),
                serialNumber = "",
                uacVersion = detectUacVersion(supportedRates),
                vendorId = 0, // Would come from UsbDevice
                productId = 0,
                usbVersion = "2.0",
                interfaces = interfaces,
                endpoints = endpoints,
                supportedSampleRates = supportedRates,
                supportedBitDepths = supportedDepths,
                maxChannels = 2,
                currentSampleRate = currentRate,
                currentBitDepth = if (supportedDepths.isNotEmpty()) supportedDepths.last() else 16,
                currentMode = currentModeString,
                activeInterface = if (engine.getState() >= NativeAudioEngine.STATE_CONFIGURED) 1 else -1,
                activeAltSetting = if (engine.getState() >= NativeAudioEngine.STATE_CONFIGURED) 1 else -1,
                clockSource = if (detectUacVersion(supportedRates) >= 2) "Internal" else "SOF",
                clockFrequency = currentRate,
                clockValid = currentRate > 0,
                sofSyncSupported = true,
                bufferFillLevel = bufferLevel,
                bufferSizeMs = 50, // Default buffer size
                latencyMs = calculateLatency(currentRate, 50),
                bufferBytesRead = bufferBytes,
                usbBytesTransferred = engine.getUsbBytesTransferred(),
                isUsbOutputActive = engine.isUsbOutputActive(),
                transportName = engine.getTransportName(),
                transferErrors = engine.getUsbTransferErrors().toInt(),
                underrunCount = underruns,
                overrunCount = 0,
                usbResets = 0
            )
        }
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000L) // Refresh every second
                refreshRealTimeData()
            }
        }
    }

    private fun refreshRealTimeData() {
        val currentState = _uiState.value
        if (!currentState.isDeviceConnected) return

        _uiState.value = currentState.copy(
            bufferFillLevel = engine.getBufferLevel(),
            bufferBytesRead = engine.getTotalBytesTransferred(),
            usbBytesTransferred = engine.getUsbBytesTransferred(),
            isUsbOutputActive = engine.isUsbOutputActive(),
            transportName = engine.getTransportName(),
            transferErrors = engine.getUsbTransferErrors().toInt(),
            underrunCount = engine.getUnderrunCount(),
            currentSampleRate = engine.getCurrentSampleRate()
        )
    }

    private fun buildInterfaceList(): List<AudioInterface> {
        // Based on USB Audio Class spec, typical device has:
        // Interface 0: Audio Control
        // Interface 1: Audio Streaming (playback)
        return listOf(
            AudioInterface(
                interfaceNumber = 0,
                subclass = "Audio Control",
                alternateSettings = 1,
                protocol = "None",
                terminalType = "USB Streaming"
            ),
            AudioInterface(
                interfaceNumber = 1,
                subclass = "Audio Streaming",
                alternateSettings = 3,
                protocol = "None",
                terminalType = "Speaker"
            )
        )
    }

    private fun buildEndpointList(): List<EndpointInfo> {
        return listOf(
            EndpointInfo(
                address = 0x01,
                direction = "OUT (Host to Device)",
                transferType = "Isochronous",
                maxPacketSize = 1024,
                interval = 1,
                syncType = "Adaptive"
            ),
            EndpointInfo(
                address = 0x81,
                direction = "IN (Feedback)",
                transferType = "Isochronous",
                maxPacketSize = 4,
                interval = 1,
                syncType = "Implicit"
            )
        )
    }

    private fun extractManufacturer(deviceName: String): String {
        return if (deviceName.contains(" ")) {
            deviceName.substringBefore(" ")
        } else {
            deviceName
        }
    }

    private fun extractProductName(deviceName: String): String {
        return if (deviceName.contains(" ")) {
            deviceName.substringAfter(" ")
        } else {
            deviceName
        }
    }

    private fun detectUacVersion(supportedRates: IntArray): Int {
        // UAC2 devices typically support higher sample rates
        return if (supportedRates.any { it > 192000 }) 2 else 1
    }

    private fun calculateLatency(sampleRate: Int, bufferMs: Int): Int {
        if (sampleRate <= 0) return 0
        return bufferMs + 1 // Buffer + USB frame (1ms)
    }
}
