package com.bitperfect.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SettingsViewModel - ViewModel for the settings screen.
 *
 * Responsibilities:
 * - Reads settings from SettingsRepository (DataStore)
 * - Validates settings changes (e.g., buffer size ranges)
 * - Shows BitPerfect mode warning when relevant settings change
 * - Exposes settings state as StateFlow for Compose
 * - Persists changes immediately via DataStore
 */
class SettingsViewModel(
    private val repository: SettingsRepository
) : ViewModel() {

    /**
     * Settings UI state.
     */
    data class SettingsUiState(
        // Audio Output
        val bitPerfectMode: Boolean = true,
        val usbOutputDevice: String = "",
        val bufferSizeMs: Int = 50,
        val latencyMs: Int = 51,

        // Format
        val autoSampleRate: Boolean = true,
        val dsdOutputMode: String = "native_dsd",
        val nativeDsdPreference: Boolean = true,
        val dopPreference: Boolean = true,
        val pcmFallbackPolicy: String = "convert",

        // Processing
        val replayGainMode: String = "off",
        val preampDb: Float = 0f,
        val clippingPrevention: Boolean = true,
        val crossfadeMs: Int = 0,

        // Equalizer
        val eqEnabled: Boolean = false,
        val eqPreset: String = "flat",
        val eqBands: String = "0,0,0,0,0,0,0,0,0,0",

        // Interface
        val themeMode: String = "system",
        val debugLogging: Boolean = false,

        // Library
        val scanDirectories: Set<String> = emptySet(),

        // Warnings
        val showBitPerfectWarning: Boolean = false,
        val bitPerfectWarningMessage: String = "",

        // App info
        val appVersion: String = "1.0.0",
        val buildInfo: String = "Release"
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _showWarningDialog = MutableStateFlow(false)
    val showWarningDialog: StateFlow<Boolean> = _showWarningDialog.asStateFlow()

    private val _warningMessage = MutableStateFlow("")
    val warningMessage: StateFlow<String> = _warningMessage.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val bitPerfect = repository.bitPerfectMode.first()
            val bufferSize = repository.bufferSizeMs.first()
            val autoRate = repository.autoSampleRate.first()
            val dsdMode = repository.dsdOutputMode.first()
            val nativeDsd = repository.nativeDsdPreference.first()
            val dop = repository.dopPreference.first()
            val pcmFallback = repository.pcmFallbackPolicy.first()
            val replayGain = repository.replayGainMode.first()
            val preamp = repository.preampDb.first()
            val clipping = repository.clippingPrevention.first()
            val crossfade = repository.crossfadeMs.first()
            val theme = repository.themeMode.first()
            val debug = repository.debugLogging.first()
            val scanDirs = repository.scanDirectories.first()
            val eqEnabled = repository.eqEnabled.first()
            val eqPreset = repository.eqPreset.first()
            val eqBands = repository.eqBands.first()

            _uiState.value = SettingsUiState(
                bitPerfectMode = bitPerfect,
                bufferSizeMs = bufferSize,
                latencyMs = bufferSize + 1,
                autoSampleRate = autoRate,
                dsdOutputMode = dsdMode,
                nativeDsdPreference = nativeDsd,
                dopPreference = dop,
                pcmFallbackPolicy = pcmFallback,
                replayGainMode = replayGain,
                preampDb = preamp,
                clippingPrevention = clipping,
                crossfadeMs = crossfade,
                themeMode = theme,
                debugLogging = debug,
                scanDirectories = scanDirs,
                eqEnabled = eqEnabled,
                eqPreset = eqPreset,
                eqBands = eqBands
            )
        }
    }

    // --- Audio Output ---

    fun setBitPerfectMode(enabled: Boolean) {
        if (!enabled) {
            showBitPerfectWarning(
                "Disabling BitPerfect mode allows the system to modify audio data. " +
                "This means sample rate conversion, volume adjustment, and other processing " +
                "may alter the audio stream before it reaches your DAC."
            )
        }
        viewModelScope.launch {
            repository.setBitPerfectMode(enabled)
            _uiState.value = _uiState.value.copy(bitPerfectMode = enabled)
        }
    }

    fun setBufferSize(sizeMs: Int) {
        val clamped = sizeMs.coerceIn(10, 500)
        viewModelScope.launch {
            repository.setBufferSizeMs(clamped)
            _uiState.value = _uiState.value.copy(
                bufferSizeMs = clamped,
                latencyMs = clamped + 1
            )
        }
    }

    fun setUsbOutputDevice(deviceId: String) {
        viewModelScope.launch {
            repository.setUsbOutputDevice(deviceId)
            _uiState.value = _uiState.value.copy(usbOutputDevice = deviceId)
        }
    }

    // --- Format ---

    fun setAutoSampleRate(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoSampleRate(enabled)
            _uiState.value = _uiState.value.copy(autoSampleRate = enabled)
        }
    }

    fun setDsdOutputMode(mode: String) {
        if (mode == "pcm" && _uiState.value.bitPerfectMode) {
            showBitPerfectWarning(
                "Converting DSD to PCM alters the original audio data. " +
                "This is not bit-perfect playback for DSD content."
            )
        }
        viewModelScope.launch {
            repository.setDsdOutputMode(mode)
            _uiState.value = _uiState.value.copy(dsdOutputMode = mode)
        }
    }

    fun setNativeDsdPreference(enabled: Boolean) {
        viewModelScope.launch {
            repository.setNativeDsdPreference(enabled)
            _uiState.value = _uiState.value.copy(nativeDsdPreference = enabled)
        }
    }

    fun setDopPreference(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDopPreference(enabled)
            _uiState.value = _uiState.value.copy(dopPreference = enabled)
        }
    }

    fun setPcmFallbackPolicy(policy: String) {
        viewModelScope.launch {
            repository.setPcmFallbackPolicy(policy)
            _uiState.value = _uiState.value.copy(pcmFallbackPolicy = policy)
        }
    }

    // --- Processing ---

    fun setReplayGainMode(mode: String) {
        if (mode != "off" && _uiState.value.bitPerfectMode) {
            showBitPerfectWarning(
                "Enabling ReplayGain modifies audio levels, which means playback " +
                "is no longer bit-perfect. The audio stream will be altered."
            )
        }
        viewModelScope.launch {
            repository.setReplayGainMode(mode)
            _uiState.value = _uiState.value.copy(replayGainMode = mode)
        }
    }

    fun setPreampDb(db: Float) {
        val clamped = db.coerceIn(-12f, 12f)
        viewModelScope.launch {
            repository.setPreampDb(clamped)
            _uiState.value = _uiState.value.copy(preampDb = clamped)
        }
    }

    fun setClippingPrevention(enabled: Boolean) {
        viewModelScope.launch {
            repository.setClippingPrevention(enabled)
            _uiState.value = _uiState.value.copy(clippingPrevention = enabled)
        }
    }

    fun setCrossfade(ms: Int) {
        val clamped = ms.coerceIn(0, 12000)
        if (clamped > 0 && _uiState.value.bitPerfectMode) {
            showBitPerfectWarning(
                "Crossfade blends audio between tracks, which modifies the audio stream. " +
                "This disables bit-perfect playback during transitions."
            )
        }
        viewModelScope.launch {
            repository.setCrossfadeMs(clamped)
            _uiState.value = _uiState.value.copy(crossfadeMs = clamped)
        }
    }

    // --- Interface ---

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            repository.setThemeMode(mode)
            _uiState.value = _uiState.value.copy(themeMode = mode)
        }
    }

    fun setDebugLogging(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDebugLogging(enabled)
            _uiState.value = _uiState.value.copy(debugLogging = enabled)
        }
    }

    // --- Equalizer ---

    fun setEqEnabled(enabled: Boolean) {
        if (enabled && _uiState.value.bitPerfectMode) {
            showBitPerfectWarning(
                "Enabling the equalizer modifies the audio signal. " +
                "This disables bit-perfect playback mode."
            )
        }
        viewModelScope.launch {
            repository.setEqEnabled(enabled)
            _uiState.value = _uiState.value.copy(eqEnabled = enabled)
        }
    }

    fun setEqPreset(preset: String) {
        viewModelScope.launch {
            repository.setEqPreset(preset)
            _uiState.value = _uiState.value.copy(eqPreset = preset)
        }
    }

    fun setEqBands(bands: String) {
        viewModelScope.launch {
            repository.setEqBands(bands)
            _uiState.value = _uiState.value.copy(eqBands = bands)
        }
    }

    // --- Library ---

    fun addScanDirectory(path: String) {
        if (path.isBlank()) return
        viewModelScope.launch {
            repository.addScanDirectory(path)
            val updated = repository.scanDirectories.first()
            _uiState.value = _uiState.value.copy(scanDirectories = updated)
        }
    }

    fun removeScanDirectory(path: String) {
        viewModelScope.launch {
            repository.removeScanDirectory(path)
            val updated = repository.scanDirectories.first()
            _uiState.value = _uiState.value.copy(scanDirectories = updated)
        }
    }

    fun getScanDirectories(): Set<String> {
        return _uiState.value.scanDirectories
    }

    // --- Warnings ---

    private fun showBitPerfectWarning(message: String) {
        _warningMessage.value = message
        _showWarningDialog.value = true
    }

    fun dismissWarning() {
        _showWarningDialog.value = false
        _warningMessage.value = ""
    }
}
