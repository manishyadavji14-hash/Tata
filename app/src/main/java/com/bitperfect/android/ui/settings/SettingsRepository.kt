package com.bitperfect.android.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * SettingsRepository - DataStore wrapper for typed settings access.
 *
 * Provides strongly-typed access to all BitPerfect settings with
 * default values and Flow-based reactive observation.
 *
 * Settings categories:
 * - Audio Output: BitPerfect mode, USB output device, buffer size, latency
 * - Format: Auto sample-rate switching, DSD output mode, Native DSD preference
 * - Processing: ReplayGain mode, preamp, clipping prevention, crossfade
 * - Interface: Theme, debug logging
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bitperfect_settings")

class SettingsRepository(private val context: Context) {

    // --- Keys ---

    private object Keys {
        // Audio Output
        val BIT_PERFECT_MODE = booleanPreferencesKey("bit_perfect_mode")
        val USB_OUTPUT_DEVICE = stringPreferencesKey("usb_output_device")
        val BUFFER_SIZE_MS = intPreferencesKey("buffer_size_ms")

        // Format
        val AUTO_SAMPLE_RATE = booleanPreferencesKey("auto_sample_rate")
        val DSD_OUTPUT_MODE = stringPreferencesKey("dsd_output_mode")
        val NATIVE_DSD_PREFERENCE = booleanPreferencesKey("native_dsd_preference")
        val DOP_PREFERENCE = booleanPreferencesKey("dop_preference")
        val PCM_FALLBACK_POLICY = stringPreferencesKey("pcm_fallback_policy")

        // Processing
        val REPLAY_GAIN_MODE = stringPreferencesKey("replay_gain_mode")
        val PREAMP_DB = floatPreferencesKey("preamp_db")
        val CLIPPING_PREVENTION = booleanPreferencesKey("clipping_prevention")
        val CROSSFADE_MS = intPreferencesKey("crossfade_ms")

        // Interface
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEBUG_LOGGING = booleanPreferencesKey("debug_logging")
    }

    // --- Default Values ---

    object Defaults {
        const val BIT_PERFECT_MODE = true
        const val BUFFER_SIZE_MS = 50
        const val AUTO_SAMPLE_RATE = true
        const val DSD_OUTPUT_MODE = "native_dsd"  // "native_dsd", "dop", "pcm"
        const val NATIVE_DSD_PREFERENCE = true
        const val DOP_PREFERENCE = true
        const val PCM_FALLBACK_POLICY = "convert"  // "convert", "skip", "ask"
        const val REPLAY_GAIN_MODE = "off"  // "off", "track", "album"
        const val PREAMP_DB = 0f
        const val CLIPPING_PREVENTION = true
        const val CROSSFADE_MS = 0
        const val THEME_MODE = "system"  // "system", "light", "dark"
        const val DEBUG_LOGGING = false
    }

    // --- Audio Output Settings ---

    val bitPerfectMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BIT_PERFECT_MODE] ?: Defaults.BIT_PERFECT_MODE
    }

    suspend fun setBitPerfectMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BIT_PERFECT_MODE] = enabled
        }
    }

    val usbOutputDevice: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.USB_OUTPUT_DEVICE] ?: ""
    }

    suspend fun setUsbOutputDevice(deviceId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.USB_OUTPUT_DEVICE] = deviceId
        }
    }

    val bufferSizeMs: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.BUFFER_SIZE_MS] ?: Defaults.BUFFER_SIZE_MS
    }

    suspend fun setBufferSizeMs(sizeMs: Int) {
        val clamped = sizeMs.coerceIn(10, 500)
        context.dataStore.edit { prefs ->
            prefs[Keys.BUFFER_SIZE_MS] = clamped
        }
    }

    // --- Format Settings ---

    val autoSampleRate: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUTO_SAMPLE_RATE] ?: Defaults.AUTO_SAMPLE_RATE
    }

    suspend fun setAutoSampleRate(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_SAMPLE_RATE] = enabled
        }
    }

    val dsdOutputMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DSD_OUTPUT_MODE] ?: Defaults.DSD_OUTPUT_MODE
    }

    suspend fun setDsdOutputMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DSD_OUTPUT_MODE] = mode
        }
    }

    val nativeDsdPreference: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NATIVE_DSD_PREFERENCE] ?: Defaults.NATIVE_DSD_PREFERENCE
    }

    suspend fun setNativeDsdPreference(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NATIVE_DSD_PREFERENCE] = enabled
        }
    }

    val dopPreference: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DOP_PREFERENCE] ?: Defaults.DOP_PREFERENCE
    }

    suspend fun setDopPreference(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DOP_PREFERENCE] = enabled
        }
    }

    val pcmFallbackPolicy: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.PCM_FALLBACK_POLICY] ?: Defaults.PCM_FALLBACK_POLICY
    }

    suspend fun setPcmFallbackPolicy(policy: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PCM_FALLBACK_POLICY] = policy
        }
    }

    // --- Processing Settings ---

    val replayGainMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.REPLAY_GAIN_MODE] ?: Defaults.REPLAY_GAIN_MODE
    }

    suspend fun setReplayGainMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REPLAY_GAIN_MODE] = mode
        }
    }

    val preampDb: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.PREAMP_DB] ?: Defaults.PREAMP_DB
    }

    suspend fun setPreampDb(db: Float) {
        val clamped = db.coerceIn(-12f, 12f)
        context.dataStore.edit { prefs ->
            prefs[Keys.PREAMP_DB] = clamped
        }
    }

    val clippingPrevention: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CLIPPING_PREVENTION] ?: Defaults.CLIPPING_PREVENTION
    }

    suspend fun setClippingPrevention(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLIPPING_PREVENTION] = enabled
        }
    }

    val crossfadeMs: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.CROSSFADE_MS] ?: Defaults.CROSSFADE_MS
    }

    suspend fun setCrossfadeMs(ms: Int) {
        val clamped = ms.coerceIn(0, 12000)
        context.dataStore.edit { prefs ->
            prefs[Keys.CROSSFADE_MS] = clamped
        }
    }

    // --- Interface Settings ---

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE] ?: Defaults.THEME_MODE
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode
        }
    }

    val debugLogging: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEBUG_LOGGING] ?: Defaults.DEBUG_LOGGING
    }

    suspend fun setDebugLogging(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEBUG_LOGGING] = enabled
        }
    }
}
