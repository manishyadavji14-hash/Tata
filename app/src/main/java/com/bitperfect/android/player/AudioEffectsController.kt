package com.bitperfect.android.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Equalizer and bass boost for the Android output path.
 *
 * This is deliberately scoped to AudioTrack playback. Platform audio effects
 * attach to an AudioTrack audio session, so they physically cannot alter a
 * bit-perfect USB stream that bypasses AudioTrack. That is what keeps the two
 * output paths honest: enabling an equalizer here cannot silently compromise
 * bit-perfect output.
 *
 * Band count is decided by the device, not by this app. Most Android devices
 * expose a 5-band equalizer; a fixed 10-band curve would require custom filters
 * in the native engine rather than the platform effect used here.
 */
class AudioEffectsController {

    companion object {
        private const val TAG = "AudioEffectsController"

        /** Platform effects use 0-1000 for strength. */
        const val MAX_STRENGTH = 1000

        /** Effect priority; higher wins when several apps attach effects. */
        private const val EFFECT_PRIORITY = 0

        /**
         * Fraction of the treble control applied to the top band, with the
         * remainder applied to the band below it so the lift is not a spike.
         */
        private const val TREBLE_TOP_BAND_SHARE = 1.0f
        private const val TREBLE_NEXT_BAND_SHARE = 0.5f
    }

    /**
     * One equalizer band as reported by the device.
     */
    data class Band(
        val index: Int,
        val centerFrequencyHz: Int
    ) {
        /** Short label such as "60 Hz" or "3.2 kHz". */
        val label: String
            get() = if (centerFrequencyHz >= 1000) {
                val khz = centerFrequencyHz / 1000.0
                if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else "%.1f kHz".format(khz)
            } else {
                "$centerFrequencyHz Hz"
            }
    }

    /**
     * What the current device actually supports.
     */
    data class Capabilities(
        val isAvailable: Boolean = false,
        val bands: List<Band> = emptyList(),
        val minLevelMillibel: Int = 0,
        val maxLevelMillibel: Int = 0,
        val supportsBassBoost: Boolean = false,
        val presets: List<String> = emptyList(),
        val unavailableReason: String? = null
    )

    /**
     * User-controlled effect state.
     *
     * @param bandLevelsMillibel One level per device band, in millibels
     *   (100 mB = 1 dB). Empty means flat.
     * @param bassBoostStrength 0-1000.
     * @param trebleStrength 0-1000, applied through the highest equalizer bands
     *   because Android has no dedicated treble effect.
     */
    data class Settings(
        val isEnabled: Boolean = false,
        val bandLevelsMillibel: List<Int> = emptyList(),
        val bassBoostStrength: Int = 0,
        val trebleStrength: Int = 0
    )

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var attachedSessionId: Int? = null

    @Volatile
    var capabilities: Capabilities = Capabilities()
        private set

    @Volatile
    var settings: Settings = Settings()
        private set

    /**
     * Attach effects to an AudioTrack session and apply the current settings.
     *
     * Safe to call for every new track: each AudioTrack has its own session, so
     * the effects are rebuilt and the retained settings re-applied.
     */
    @Synchronized
    fun attach(audioSessionId: Int) {
        if (attachedSessionId == audioSessionId && equalizer != null) {
            applySettingsLocked()
            return
        }

        detach()

        try {
            val createdEqualizer = Equalizer(EFFECT_PRIORITY, audioSessionId)
            val bandCount = createdEqualizer.numberOfBands.toInt()
            val levelRange = createdEqualizer.bandLevelRange

            val bands = (0 until bandCount).map { index ->
                Band(
                    index = index,
                    // getCenterFreq reports milliHertz.
                    centerFrequencyHz = createdEqualizer.getCenterFreq(index.toShort()) / 1000
                )
            }

            val presetNames = (0 until createdEqualizer.numberOfPresets.toInt()).mapNotNull {
                runCatching { createdEqualizer.getPresetName(it.toShort()) }.getOrNull()
            }

            equalizer = createdEqualizer

            val createdBassBoost = try {
                BassBoost(EFFECT_PRIORITY, audioSessionId).takeIf { it.strengthSupported }
            } catch (error: Exception) {
                Log.w(TAG, "Bass boost unavailable: ${error.message}")
                null
            }
            bassBoost = createdBassBoost

            capabilities = Capabilities(
                isAvailable = true,
                bands = bands,
                minLevelMillibel = levelRange.getOrElse(0) { 0 }.toInt(),
                maxLevelMillibel = levelRange.getOrElse(1) { 0 }.toInt(),
                supportsBassBoost = createdBassBoost != null,
                presets = presetNames
            )
            attachedSessionId = audioSessionId

            // Resize the level list to this device's band count, preserving the
            // overlapping values rather than discarding the saved curve.
            if (settings.bandLevelsMillibel.size != bands.size) {
                settings = settings.copy(
                    bandLevelsMillibel = List(bands.size) { index ->
                        settings.bandLevelsMillibel.getOrElse(index) { 0 }
                    }
                )
            }

            applySettingsLocked()
        } catch (error: Exception) {
            // Effects are optional; playback must continue without them.
            Log.w(TAG, "Audio effects unavailable on this device: ${error.message}")
            releaseLocked()
            capabilities = Capabilities(
                isAvailable = false,
                unavailableReason = "This device does not offer an equalizer for app audio"
            )
        }
    }

    /**
     * Release the effects owned by a specific session.
     *
     * The session id makes teardown idempotent per owner. Without it, a
     * playback worker that exits late would release effects belonging to the
     * track that has already started, silently disabling the equalizer while
     * the UI still reported it as active.
     *
     * @param sessionId Session whose effects should be released. Null forces a
     *   release regardless of owner, for shutdown.
     */
    @Synchronized
    fun detach(sessionId: Int? = null) {
        if (sessionId != null && attachedSessionId != null && attachedSessionId != sessionId) {
            // A newer track already owns the effects; leave them alone.
            return
        }
        releaseLocked()
        attachedSessionId = null
        capabilities = Capabilities()
    }

    /**
     * Turn all effects on or off, keeping the configured curve.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        settings = settings.copy(isEnabled = enabled)
        applySettingsLocked()
    }

    @Synchronized
    fun setBandLevel(bandIndex: Int, levelMillibel: Int) {
        val bandCount = capabilities.bands.size
        if (bandIndex !in 0 until bandCount) return

        val clamped = levelMillibel.coerceIn(
            capabilities.minLevelMillibel,
            capabilities.maxLevelMillibel
        )
        val levels = settings.bandLevelsMillibel
            .toMutableList()
            .also { list ->
                while (list.size < bandCount) list.add(0)
                list[bandIndex] = clamped
            }

        settings = settings.copy(bandLevelsMillibel = levels)
        applySettingsLocked()
    }

    @Synchronized
    fun setBassBoostStrength(strength: Int) {
        settings = settings.copy(bassBoostStrength = strength.coerceIn(0, MAX_STRENGTH))
        applySettingsLocked()
    }

    @Synchronized
    fun setTrebleStrength(strength: Int) {
        settings = settings.copy(trebleStrength = strength.coerceIn(0, MAX_STRENGTH))
        applySettingsLocked()
    }

    /**
     * Reset the curve to flat and disable boosts.
     */
    @Synchronized
    fun resetToFlat() {
        settings = settings.copy(
            bandLevelsMillibel = List(capabilities.bands.size) { 0 },
            bassBoostStrength = 0,
            trebleStrength = 0
        )
        applySettingsLocked()
    }

    /**
     * Apply a device preset, reading the resulting curve back into settings.
     */
    @Synchronized
    fun applyPreset(presetIndex: Int) {
        val activeEqualizer = equalizer ?: return
        if (presetIndex !in capabilities.presets.indices) return

        try {
            activeEqualizer.usePreset(presetIndex.toShort())
            val levels = capabilities.bands.map { band ->
                activeEqualizer.getBandLevel(band.index.toShort()).toInt()
            }
            settings = settings.copy(isEnabled = true, bandLevelsMillibel = levels)
            applySettingsLocked()
        } catch (error: Exception) {
            Log.w(TAG, "Could not apply preset $presetIndex: ${error.message}")
        }
    }

    /**
     * Restore persisted settings, resizing the curve to this device.
     */
    @Synchronized
    fun restoreSettings(restored: Settings) {
        val bandCount = capabilities.bands.size
        val levels = when {
            bandCount == 0 -> restored.bandLevelsMillibel
            restored.bandLevelsMillibel.size == bandCount -> restored.bandLevelsMillibel
            else -> List(bandCount) { index ->
                restored.bandLevelsMillibel.getOrElse(index) { 0 }
            }
        }
        settings = restored.copy(bandLevelsMillibel = levels)
        applySettingsLocked()
    }

    // --- Internals (callers already hold the monitor) ---

    private fun applySettingsLocked() {
        val activeEqualizer = equalizer
        val activeBassBoost = bassBoost
        val current = settings

        try {
            activeEqualizer?.let { eq ->
                eq.enabled = current.isEnabled
                if (current.isEnabled) {
                    val trebleLevels = trebleLevelsMillibel(current.trebleStrength)
                    capabilities.bands.forEach { band ->
                        val base = current.bandLevelsMillibel.getOrElse(band.index) { 0 }
                        val total = (base + trebleLevels.getOrElse(band.index) { 0 })
                            .coerceIn(
                                capabilities.minLevelMillibel,
                                capabilities.maxLevelMillibel
                            )
                        eq.setBandLevel(band.index.toShort(), total.toShort())
                    }
                }
            }

            activeBassBoost?.let { boost ->
                boost.enabled = current.isEnabled && current.bassBoostStrength > 0
                if (boost.enabled) {
                    boost.setStrength(current.bassBoostStrength.toShort())
                }
            }
        } catch (error: Exception) {
            // A device can refuse an effect change mid-stream; keep playing.
            Log.w(TAG, "Could not apply audio effect settings: ${error.message}")
        }
    }

    /**
     * Spread the treble control across the highest bands.
     *
     * Android exposes no treble effect, so this is expressed as extra gain on
     * the top of the equalizer curve.
     */
    private fun trebleLevelsMillibel(strength: Int): List<Int> {
        val bandCount = capabilities.bands.size
        if (bandCount == 0 || strength <= 0) return List(bandCount) { 0 }

        val headroom = capabilities.maxLevelMillibel.coerceAtLeast(0)
        val peak = (headroom * strength / MAX_STRENGTH.toFloat()).toInt()

        return List(bandCount) { index ->
            when (index) {
                bandCount - 1 -> (peak * TREBLE_TOP_BAND_SHARE).toInt()
                bandCount - 2 -> (peak * TREBLE_NEXT_BAND_SHARE).toInt()
                else -> 0
            }
        }
    }

    private fun releaseLocked() {
        try {
            equalizer?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Equalizer release failed: ${error.message}")
        }
        try {
            bassBoost?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Bass boost release failed: ${error.message}")
        }
        equalizer = null
        bassBoost = null
    }
}
