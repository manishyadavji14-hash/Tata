package com.bitperfect.android.ui.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.android.BitPerfectApp
import com.bitperfect.android.player.AudioEffectsController
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState
import com.bitperfect.android.ui.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /**
     * Null while a USB DAC is the output: platform effects attach to an
     * AudioTrack session, and a bit-perfect stream has none. Every mutator below
     * no-ops in that case, and the UI shows why via `statusMessage`.
     */
    private val effects: AudioEffectsController? get() = playbackController.audioEffects

    private val playbackListener: (PlaybackState) -> Unit = { refreshFromController() }

    /** Pending debounce timer, cancelled and replaced by each new change. */
    private var persistJob: Job? = null

    /**
     * True while a debounced write is waiting. Distinct from `persistJob`
     * being active, which cannot tell a pending debounce from a write already
     * in flight.
     */
    private var hasPendingDebounce: Boolean = false

    init {
        playbackController.addStateListener(playbackListener)

        viewModelScope.launch {
            // Restore the saved curve so it applies as soon as effects attach.
            val stored = settingsRepository.equalizerSettings.first()
            effects?.restoreSettings(stored)
            refreshFromController()
        }
    }

    fun setEnabled(enabled: Boolean) {
        effects?.setEnabled(enabled)
        applyAndPersistNow()
    }

    /**
     * Applies a band level immediately but only schedules the save.
     *
     * A slider drag emits a value for every pixel of travel. The effect has to
     * follow the finger so the change is audible at once, while the on-disk
     * copy only needs the value the drag settles on.
     */
    fun setBandLevel(bandIndex: Int, levelMillibel: Int) {
        effects?.setBandLevel(bandIndex, levelMillibel)
        applyAndSchedulePersist()
    }

    fun setBassBoost(strength: Int) {
        effects?.setBassBoostStrength(strength)
        applyAndSchedulePersist()
    }

    fun setTreble(strength: Int) {
        effects?.setTrebleStrength(strength)
        applyAndSchedulePersist()
    }

    /**
     * Flushes a pending slider value as soon as a drag ends, so the setting is
     * on disk without waiting out the debounce.
     */
    fun commitPendingChanges() {
        // Only a debounced write is worth flushing. An immediate write is
        // already on its way, and re-issuing it would store the same value
        // twice.
        if (!hasPendingDebounce) return
        persistNow()
    }

    fun applyPreset(presetIndex: Int) {
        effects?.applyPreset(presetIndex)
        applyAndPersistNow()
    }

    fun resetToFlat() {
        effects?.resetToFlat()
        applyAndPersistNow()
    }

    /**
     * Discrete actions such as a preset or a toggle happen once, so they are
     * written straight away rather than debounced.
     */
    private fun applyAndPersistNow() {
        persistNow()
        refreshFromController()
    }

    private fun applyAndSchedulePersist() {
        persistJob?.cancel()
        hasPendingDebounce = true
        // The delay is tied to the ViewModel, but the write it triggers is not:
        // see persistNow.
        persistJob = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistNow()
        }
        refreshFromController()
    }

    /**
     * Writes the current curve on the application scope.
     *
     * Deliberately not viewModelScope: clearing the ViewModel would cancel the
     * write and silently lose the setting. Reading `effects.settings` here
     * rather than at schedule time means the newest value is stored, not
     * whichever change happened to trigger this write.
     */
    private fun persistNow() {
        persistJob?.cancel()
        hasPendingDebounce = false
        val snapshot = effects?.settings ?: return
        BitPerfectApp.applicationScope.launch {
            settingsRepository.setEqualizerSettings(snapshot)
        }
    }

    private fun refreshFromController() {
        val activeEffects = effects
        if (activeEffects == null) {
            // Bit-perfect output. Report it plainly rather than showing a dead
            // set of sliders that appear to work.
            _uiState.value = EqualizerUiState(
                isAttached = false,
                statusMessage = "Equalizer is unavailable while playing to " +
                    "${playbackController.outputName}, because it would alter the " +
                    "bit-perfect signal."
            )
            return
        }

        val capabilities = activeEffects.capabilities
        val settings = activeEffects.settings

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
        // Flush rather than drop: a value changed through accessibility or a
        // keyboard never fires onValueChangeFinished, so this may be the only
        // chance to store it.
        if (hasPendingDebounce) persistNow()
        playbackController.removeStateListener(playbackListener)
        super.onCleared()
    }

    private companion object {
        /**
         * Long enough to collapse a whole drag into one write, short enough
         * that the value is on disk before the user can leave the screen.
         */
        const val PERSIST_DEBOUNCE_MS = 300L
    }
}
