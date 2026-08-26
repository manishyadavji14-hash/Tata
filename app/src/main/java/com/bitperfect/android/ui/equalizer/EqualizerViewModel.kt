package com.bitperfect.android.ui.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.player.AudioEffectsController
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.ui.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the equalizer screen.
 *
 * The equalizer belongs to the Android output path. Platform audio effects bind
 * to an AudioTrack session, so they cannot alter bit-perfect USB output - the
 * architecture enforces the separation rather than a runtime check.
 *
 * Effects only exist while a track is playing, because the session they attach
 * to belongs to the active AudioTrack. Controls are therefore shown as inactive
 * until playback starts, and the chosen curve is persisted and re-applied.
 */
class EqualizerViewModel(
    private val playbackController: PlaybackController,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    data class BandState(
        val index: Int,
        val label: String,
        val levelMillibel: Int
    ) {
        val levelDb: Float
            get() = levelMillibel / 100f
    }

    data class EqualizerUiState(
        val isAttached: Boolean = false,
        val isEnabled: Boolean = false,
        val bands: List<BandState> = emptyList(),
        val minLevelMillibel: Int = -1500,
        val maxLevelMillibel: Int = 1500,
        val bassBoostStrength: Int = 0,
        val trebleStrength: Int = 0,
        val supportsBassBoost: Boolean = false,
        val presets: List<String> = emptyList(),
        val statusMessage: String? = null
    ) {
        val maxLevelDb: Float get() = maxLevelMillibel / 100f
        val minLevelDb: Float get() = minLevelMillibel / 100f
    }

    private val _uiState = MutableStateFlow(EqualizerUiState())
    val uiState: StateFlow<EqualizerUiState> = _uiState.asStateFlow()

    private val effects: AudioEffectsController get() = playbackController.audioEffects

    private val playbackListener: (PlaybackState) -> Unit = { refreshFromController() }

    init {
        playbackController.addStateListener(playbackListener)

        viewModelScope.launch {
            // Restore the saved curve so it applies as soon as effects attach.
            val stored = settingsRepository.equalizerSettings.first()
            effects.restoreSettings(stored)
            refreshFromController()
        }
    }

    fun setEnabled(enabled: Boolean) {
        effects.setEnabled(enabled)
        persistAndRefresh()
    }

    fun setBandLevel(bandIndex: Int, levelMillibel: Int) {
        effects.setBandLevel(bandIndex, levelMillibel)
        persistAndRefresh()
    }

    fun setBassBoost(strength: Int) {
        effects.setBassBoostStrength(strength)
        persistAndRefresh()
    }

    fun setTreble(strength: Int) {
        effects.setTrebleStrength(strength)
        persistAndRefresh()
    }

    fun applyPreset(presetIndex: Int) {
        effects.applyPreset(presetIndex)
        persistAndRefresh()
    }

    fun resetToFlat() {
        effects.resetToFlat()
        persistAndRefresh()
    }

    private fun persistAndRefresh() {
        val current = effects.settings
        viewModelScope.launch { settingsRepository.setEqualizerSettings(current) }
        refreshFromController()
    }

    private fun refreshFromController() {
        val capabilities = effects.capabilities
        val settings = effects.settings

        _uiState.value = EqualizerUiState(
            isAttached = capabilities.isAvailable,
            isEnabled = settings.isEnabled,
            bands = capabilities.bands.map { band ->
                BandState(
                    index = band.index,
                    label = band.label,
                    levelMillibel = settings.bandLevelsMillibel.getOrElse(band.index) { 0 }
                )
            },
            minLevelMillibel = if (capabilities.isAvailable) {
                capabilities.minLevelMillibel
            } else {
                -1500
            },
            maxLevelMillibel = if (capabilities.isAvailable) {
                capabilities.maxLevelMillibel
            } else {
                1500
            },
            bassBoostStrength = settings.bassBoostStrength,
            trebleStrength = settings.trebleStrength,
            supportsBassBoost = capabilities.supportsBassBoost,
            presets = capabilities.presets,
            statusMessage = when {
                capabilities.unavailableReason != null -> capabilities.unavailableReason
                !capabilities.isAvailable ->
                    "Start playing a track to enable the equalizer"
                else -> null
            }
        )
    }

    override fun onCleared() {
        playbackController.removeStateListener(playbackListener)
        super.onCleared()
    }
}
