package com.bitperfect.android.engine

/**
 * NativeAudioEngine - Kotlin JNI bridge to the native C++ audio engine.
 *
 * All methods correspond to functions in native_bridge.cpp.
 * This class manages the lifecycle of the native engine and provides
 * a Kotlin-friendly API for the rest of the application.
 */
class NativeAudioEngine {

    companion object {
        // Engine states (must match native EngineState enum)
        const val STATE_UNINITIALIZED = 0
        const val STATE_INITIALIZED = 1
        const val STATE_CONFIGURED = 2
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 4
        const val STATE_STOPPED = 5
        const val STATE_ERROR = 6

        // PCM formats (must match native PcmFormat enum)
        const val FORMAT_S16_LE = 0
        const val FORMAT_S24_3LE = 1
        const val FORMAT_S24_LE = 2
        const val FORMAT_S32_LE = 3
        const val FORMAT_FLOAT_LE = 4
    }

    /**
     * Initialize the native engine.
     * @return true if initialization succeeded
     */
    external fun initialize(): Boolean

    /**
     * Shutdown the native engine and release all resources.
     */
    external fun shutdown()

    /**
     * Parse USB device descriptors.
     * @param descriptorData Raw USB configuration descriptor bytes
     * @return true if a USB audio device was found in the descriptors
     */
    external fun parseDevice(descriptorData: ByteArray): Boolean

    /**
     * Configure the engine for playback.
     * @param sampleRate Desired sample rate in Hz
     * @param format PCM format (FORMAT_S16_LE, FORMAT_S24_3LE, etc.)
     * @param channels Number of channels
     * @param bufferSizeMs Buffer size in milliseconds
     * @return true if configuration succeeded
     */
    external fun configure(sampleRate: Int, format: Int, channels: Int, bufferSizeMs: Int): Boolean

    /**
     * Start playback.
     * @return true if playback started successfully
     */
    external fun startPlayback(): Boolean

    /**
     * Pause playback.
     * @return true if paused successfully
     */
    external fun pausePlayback(): Boolean

    /**
     * Resume playback from paused state.
     * @return true if resumed successfully
     */
    external fun resumePlayback(): Boolean

    /**
     * Stop playback.
     */
    external fun stopPlayback()

    /**
     * Write audio data to the engine buffer.
     * @param data PCM audio data
     * @param offset Offset into the byte array
     * @param length Number of bytes to write
     * @return Number of bytes accepted by the engine
     */
    external fun writeAudioData(data: ByteArray, offset: Int, length: Int): Int

    /**
     * Get current engine state.
     * @return One of STATE_* constants
     */
    external fun getState(): Int

    /**
     * Get current buffer fill level.
     * @return Fill level as a float between 0.0 and 1.0
     */
    external fun getBufferLevel(): Float

    /**
     * Get current sample rate.
     * @return Sample rate in Hz
     */
    external fun getCurrentSampleRate(): Int

    /**
     * Get supported sample rates for the connected device.
     * @return Array of supported sample rates in Hz
     */
    external fun getSupportedSampleRates(): IntArray

    /**
     * Get supported bit depths for the connected device.
     * @return Array of supported bit depths (16, 24, 32)
     */
    external fun getSupportedBitDepths(): IntArray

    /**
     * Get total number of buffer underruns since start.
     * @return Underrun count
     */
    external fun getUnderrunCount(): Int

    /**
     * Get total bytes transferred to USB.
     * @return Total bytes transferred
     */
    external fun getTotalBytesTransferred(): Long

    /**
     * Get device name string.
     * @return Device name or empty string if no device connected
     */
    external fun getDeviceName(): String
}
