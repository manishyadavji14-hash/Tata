package com.bitperfect.android.engine

/**
 * DsdManager - Kotlin layer for DSD file handling and mode selection coordination.
 *
 * Manages DSD playback lifecycle:
 * - Detects DSD source files (DSF format)
 * - Coordinates with native layer for format detection and mode selection
 * - Exposes playback mode (PCM/DoP/Native DSD) to UI
 * - Handles transitions between modes based on DAC capabilities
 *
 * All heavy lifting (parsing, encoding, transport) happens in native C++ via JNI.
 */
class DsdManager {

    companion object {
        // Playback modes (must match native PlaybackMode enum)
        const val MODE_PCM = 0
        const val MODE_DOP = 1
        const val MODE_NATIVE_DSD = 2

        // DSD rates
        const val DSD64_RATE = 2822400
        const val DSD128_RATE = 5644800
        const val DSD256_RATE = 11289600

        // DoP transport rates
        const val DOP_RATE_DSD64 = 176400
        const val DOP_RATE_DSD128 = 352800
        const val DOP_RATE_DSD256 = 705600
    }

    /**
     * Detect the audio format of a file from its header bytes.
     * @param headerBytes First 128+ bytes of the file
     * @return Format info array: [fileType, contentType, sampleRate, bitDepth, channels]
     *         or null if detection failed
     */
    external fun detectFormat(headerBytes: ByteArray): IntArray?

    /**
     * Check if the connected DAC supports native DSD.
     * Must be called after device descriptors have been parsed.
     * @return true if native DSD is available
     */
    external fun isNativeDsdAvailable(): Boolean

    /**
     * Get supported native DSD rates.
     * @return Array of supported DSD rates (e.g., [2822400, 5644800])
     */
    external fun getSupportedDsdRates(): IntArray

    /**
     * Select the best playback mode for a given source.
     * @param sampleRate Source sample rate (DSD rate for DSD files)
     * @param isDsd true if source is DSD content
     * @return Selected mode (MODE_PCM, MODE_DOP, or MODE_NATIVE_DSD)
     */
    external fun selectPlaybackMode(sampleRate: Int, isDsd: Boolean): Int

    /**
     * Get the transport rate for the current mode.
     * For PCM: the PCM sample rate
     * For DoP: the PCM transport rate (176400, 352800, or 705600)
     * For Native DSD: the DSD sample rate
     * @return Transport rate in Hz
     */
    external fun getTransportRate(): Int

    /**
     * Get current playback mode.
     * @return One of MODE_PCM, MODE_DOP, MODE_NATIVE_DSD
     */
    external fun getCurrentMode(): Int

    /**
     * Get human-readable mode name for display.
     * @param mode One of MODE_PCM, MODE_DOP, MODE_NATIVE_DSD
     * @return Mode name string ("PCM", "DoP", "Native DSD")
     */
    fun getModeName(mode: Int): String {
        return when (mode) {
            MODE_PCM -> "PCM"
            MODE_DOP -> "DoP"
            MODE_NATIVE_DSD -> "Native DSD"
            else -> "Unknown"
        }
    }

    /**
     * Get DSD multiplier description (e.g., "DSD64", "DSD128", "DSD256").
     * @param rate DSD sample rate
     * @return Human-readable DSD rate description
     */
    fun getDsdDescription(rate: Int): String {
        return when (rate) {
            DSD64_RATE -> "DSD64"
            DSD128_RATE -> "DSD128"
            DSD256_RATE -> "DSD256"
            else -> "DSD (${rate}Hz)"
        }
    }

    /**
     * Check if a file extension indicates DSD content.
     * @param filename File name or path
     * @return true if the extension is a known DSD format
     */
    fun isDsdFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext == "dsf" || ext == "dff"
    }

    /**
     * Calculate the DoP transport rate for a given DSD rate.
     * @param dsdRate DSD sample rate
     * @return DoP transport rate, or 0 if not a valid DSD rate
     */
    fun calculateDopRate(dsdRate: Int): Int {
        return when (dsdRate) {
            DSD64_RATE -> DOP_RATE_DSD64
            DSD128_RATE -> DOP_RATE_DSD128
            DSD256_RATE -> DOP_RATE_DSD256
            else -> 0
        }
    }
}
