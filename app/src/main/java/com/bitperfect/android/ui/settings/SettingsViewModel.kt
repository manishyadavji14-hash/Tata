package com.bitperfect.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.library.MusicLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    private val repository: SettingsRepository,
    /**
     * Needed for the album-art rebuild. Optional so the ViewModel stays
     * constructible in a test without a database.
     */
    private val musicLibrary: MusicLibrary? = null
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

        // Interface
        val themeMode: String = "system",
        val debugLogging: Boolean = false,

        // Warnings
        val showBitPerfectWarning: Boolean = false,
        val bitPerfectWarningMessage: String = "",

        // App info
        val appVersion: String = "1.0.0",
        val buildInfo: String = "Release",
        val creator: String = "Maneesh Yadav",

        // Library maintenance
        val isRebuildingArtwork: Boolean = false,
        val libraryMessage: String? = null
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * Re-read covers for tracks whose recorded artwork cannot be displayed.
     *
     * For libraries scanned before covers were extracted during a scan. Those rows
     * hold a `content://…/albumart/<id>` URI, which is deprecated and normally
     * resolves to nothing on current Android, so they show a placeholder
     * everywhere. This reads each affected file once and caches the cover found
     * inside it — cheaper and less disruptive than a full rescan, and offered as an
     * explicit action because it does touch every affected file.
     */
    fun rebuildArtwork() {
        val library = musicLibrary ?: return
        if (_uiState.value.isRebuildingArtwork) return

        viewModelScope.launch {
            _uiState.update { it.copy(isRebuildingArtwork = true, libraryMessage = null) }
            val message = try {
                val result = library.rebuildArtwork()
                buildString {
                    append("${result.alreadyUsable + result.repaired} of ")
                    append("${result.totalTracks} tracks have album art")
                    if (result.repaired > 0) append(" (${result.repaired} just recovered)")

                    // Broken down by container, because that is the axis artwork
                    // support varies along. It turns "art is missing" into something
                    // specific, and it is the one thing that can be reported from a
                    // phone without logs.
                    if (result.withoutArtwork > 0) {
                        append(". No cover found in ${result.withoutArtwork}: ")
                        append(result.missingSummary)
                    }
                }
            } catch (error: Exception) {
                "Could not rebuild album art: ${error.message}"
            }
            _uiState.update { it.copy(isRebuildingArtwork = false, libraryMessage = message) }
        }
    }

    fun dismissLibraryMessage() {
        _uiState.update { it.copy(libraryMessage = null) }
    }

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
                debugLogging = debug
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

    // Equalizer settings are owned by EqualizerViewModel, which drives a real
    // AudioEffectsController on the AudioTrack session. Settings only links to
    // that screen, so there is one place where the curve can be changed.
    //
    // Scan directories are owned by LibraryViewModel, behind the folder picker
    // on the Library screen. It writes the same SCAN_DIRECTORIES preference.

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
