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
         * Lowest centre frequency the treble control lifts.
         *
         * Chosen by ear rather than by band index. The previous version boosted
         * the top band fully and the one below it at half, which on the five-band
         * equalizer most devices expose meant nearly all the gain landed at
         * 14 kHz — where there is very little musical content — so the control
         * appeared to do nothing. Treble that people can hear starts around
         * 2 kHz, and selecting by frequency also behaves correctly on the
         * ten-band equalizers some devices report.
         */
        private const val TREBLE_FROM_HZ = 2_000

        /** Highest centre frequency the bass control lifts. */
        private const val BASS_TO_HZ = 250

        /**
         * Gain applied at the very edge of each shelf, as a fraction of the
         * control. Bands further into the shelf ramp up to the full amount, so it
         * is a shelf rather than a step.
         */
        private const val SHELF_EDGE_SHARE = 0.45f

        /**
         * Gain per band for a shelf at one end of the spectrum.
         *
         * Bands outside the shelf get nothing. Inside it the gain ramps from
         * [SHELF_EDGE_SHARE] of the control at the band nearest the middle of the
         * spectrum up to the full amount at the extreme, so the result is a shelf
         * rather than one spiking band.
         *
         * Pure and in the companion so the shaping can be unit tested — the rest
         * of this class needs a real `AudioTrack` session to do anything.
         */
        internal fun shelfLevels(
            bands: List<Band>,
            maxLevelMillibel: Int,
            strength: Int,
            high: Boolean
        ): List<Int> {
            if (bands.isEmpty() || strength <= 0) return List(bands.size) { 0 }

            val headroom = maxLevelMillibel.coerceAtLeast(0)
            val peak = headroom * strength / MAX_STRENGTH.toFloat()

            val inShelf = bands.filter {
                if (high) {
                    it.centerFrequencyHz >= TREBLE_FROM_HZ
                } else {
                    it.centerFrequencyHz in 1..BASS_TO_HZ
                }
            }

            if (inShelf.isEmpty()) {
                // No band sits in the shelf — a very coarse equalizer. Fall back to
                // the outermost band so the control is not silently inert.
                val fallback = if (high) bands.last() else bands.first()
                return bands.map { if (it.index == fallback.index) peak.toInt() else 0 }
            }

            // Order from the middle of the spectrum outward, so the ramp always
            // runs towards the extreme whichever shelf this is.
            val ordered = if (high) inShelf else inShelf.reversed()

            return bands.map { band ->
                val position = ordered.indexOfFirst { it.index == band.index }
                if (position < 0) {
                    0
                } else {
                    val share = if (ordered.size == 1) {
                        1f
                    } else {
                        SHELF_EDGE_SHARE +
                            (1f - SHELF_EDGE_SHARE) * position / (ordered.size - 1)
                    }
                    (peak * share).toInt()
                }
            }
        }
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
        /**
         * True when the platform `BassBoost` effect is doing the work, false when
         * it is applied as a low shelf on the equalizer instead. Only affects how
         * the control is described.
         */
        val usesBassBoostEffect: Boolean = false,
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
                BassBoost(EFFECT_PRIORITY, audioSessionId).let { boost ->
                    if (boost.strengthSupported) {
                        boost
                    } else {
                        // Release it rather than dropping the reference: an
                        // unreleased effect holds a native handle for the life of
                        // the process, and one leaked per track adds up.
                        runCatching { boost.release() }
                        null
                    }
                }
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
                // True even without the platform effect: the control still works,
                // applied as a low shelf on the equalizer. Reporting false greyed
                // the slider out on devices that have no BassBoost, which read as
                // "bass boost does nothing".
                supportsBassBoost = true,
                usesBassBoostEffect = createdBassBoost != null,
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
                    // The bass shelf is only used when the platform effect is not
                    // available, so the two never stack into an unpredictable
                    // amount of low end.
                    val bassLevels = if (activeBassBoost == null) {
                        bassLevelsMillibel(current.bassBoostStrength)
                    } else {
                        emptyList()
                    }

                    capabilities.bands.forEach { band ->
                        val base = current.bandLevelsMillibel.getOrElse(band.index) { 0 }
                        val total = (
                            base +
                                trebleLevels.getOrElse(band.index) { 0 } +
                                bassLevels.getOrElse(band.index) { 0 }
                            ).coerceIn(
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
     * Treble as a high shelf across every band from [TREBLE_FROM_HZ] upward.
     *
     * Android exposes no treble effect, so it is expressed as gain on the top of
     * the equalizer curve. Selecting bands by frequency instead of by index is
     * what makes it audible: on the usual five-band equalizer the old
     * index-based version put almost all of the gain at 14 kHz.
     */
    internal fun trebleLevelsMillibel(strength: Int): List<Int> =
        shelfLevelsMillibel(strength, high = true)

    /**
     * Bass as a low shelf up to [BASS_TO_HZ].
     *
     * Used only when the device has no usable `BassBoost` effect, so the control
     * still does something instead of being greyed out with no explanation.
     */
    internal fun bassLevelsMillibel(strength: Int): List<Int> =
        shelfLevelsMillibel(strength, high = false)

    /**
     * Gain per band for a shelf at one end of the spectrum.
     *
     * Bands outside the shelf get nothing. Inside it the gain ramps from
     * [SHELF_EDGE_SHARE] of the control at the band nearest the middle of the
     * spectrum up to the full amount at the extreme, so the result is a shelf
     * rather than a single spiking band.
     */
    private fun shelfLevelsMillibel(strength: Int, high: Boolean): List<Int> =
        shelfLevels(
            bands = capabilities.bands,
            maxLevelMillibel = capabilities.maxLevelMillibel,
            strength = strength,
            high = high
        )

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
