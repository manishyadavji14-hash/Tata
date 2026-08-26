package com.bitperfect.android.engine

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Detect the audio format of a file.
     * Opens the file with the appropriate decoder and returns format info.
     * @param path File path to analyze
     * @return DetectedFormat with sample rate, channels, and native format code
     */
    fun detectFileFormat(path: String): DetectedFormat {
        val formatInfo = nativeDetectFormat(path)
        // formatInfo is [sampleRate, bitsPerSample, channels]
        if (formatInfo == null || formatInfo.size < 3) {
            return DetectedFormat(0, FORMAT_S16_LE, 0)
        }
        val sampleRate = formatInfo[0]
        val bitsPerSample = formatInfo[1]
        val channels = formatInfo[2]
        val nativeFormat = when {
            bitsPerSample >= 32 -> FORMAT_S32_LE
            bitsPerSample >= 24 -> FORMAT_S24_3LE
            else -> FORMAT_S16_LE
        }
        return DetectedFormat(sampleRate, nativeFormat, channels)
    }

    /**
     * Get current bit depth configured in the engine.
     * @return Bit depth (16, 24, or 32)
     */
    external fun getCurrentBitDepth(): Int

    /**
     * Get current channel count configured in the engine.
     * @return Number of channels
     */
    external fun getCurrentChannels(): Int

    /**
     * Native method to detect file format.
     * @return IntArray of [sampleRate, bitsPerSample, channels] or null
     */
    private external fun nativeDetectFormat(path: String): IntArray?

    /**
     * Open a persistent WAV or FLAC decoder session for incremental PCM reads.
     * The caller must close the returned session.
     */
    fun openDecoder(path: String): DecoderSession? {
        val handle = nativeOpenDecoder(path)
        if (handle == 0L) return null

        val values = nativeGetDecoderFormat(handle)
        if (values == null || values.size < 4) {
            nativeCloseDecoder(handle)
            return null
        }

        val sampleRate = values[0].toInt()
        val bitsPerSample = values[1].toInt()
        val channels = values[2].toInt()
        val totalFrames = values[3]
        if (sampleRate <= 0 || bitsPerSample <= 0 || channels <= 0 || totalFrames < 0) {
            nativeCloseDecoder(handle)
            return null
        }

        return DecoderSession(
            engine = this,
            handle = handle,
            format = DecoderFormat(sampleRate, bitsPerSample, channels, totalFrames)
        )
    }

    data class DecoderFormat(
        val sampleRate: Int,
        val bitsPerSample: Int,
        val channels: Int,
        val totalFrames: Long
    ) {
        val bytesPerFrame: Int
            get() = (bitsPerSample / 8) * channels

        val durationMs: Long
            get() = if (sampleRate > 0) {
                (totalFrames / sampleRate) * 1000L +
                    (totalFrames % sampleRate) * 1000L / sampleRate
            } else {
                0L
            }
    }

    class DecoderSession internal constructor(
        private val engine: NativeAudioEngine,
        private val handle: Long,
        val format: DecoderFormat
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        fun read(output: ByteBuffer, maxFrames: Int): Int {
            require(output.isDirect) { "Decoder output buffer must be direct" }
            check(!closed.get()) { "Decoder session is closed" }
            return engine.nativeReadDecoder(handle, output, maxFrames)
        }

        /** Returns the actual resulting frame, or -1 when seeking fails. */
        fun seek(frameIndex: Long): Long {
            require(frameIndex >= 0) { "Frame index must be non-negative" }
            check(!closed.get()) { "Decoder session is closed" }
            return engine.nativeSeekDecoder(handle, frameIndex)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                engine.nativeCloseDecoder(handle)
            }
        }
    }

    // --- USB hardware attachment ---
    //
    // Android does not permit native code to open a USB device, so the Java layer
    // opens it, claims the streaming interface, selects the alternate setting and
    // hands the file descriptor down. Without this the native transport has
    // nothing to submit against and playback is silently discarded.

    /**
     * Attach an opened USB audio device to the native transport.
     *
     * The caller must already have claimed [interfaceNumber] and selected
     * [altSetting] on the connection that produced [fileDescriptor]. Native code
     * duplicates the descriptor, so the caller keeps ownership of its own copy.
     *
     * Call after [parseDevice] and before [configure].
     */
    fun attachUsbDevice(fileDescriptor: Int, interfaceNumber: Int, altSetting: Int): Boolean =
        nativeAttachUsbDevice(fileDescriptor, interfaceNumber, altSetting)

    /** Detach the USB device. Stops playback first. */
    fun detachUsbDevice() = nativeDetachUsbDevice()

    /**
     * Whether audio is actually being transmitted to a USB device.
     *
     * Deliberately distinct from [getState] returning [STATE_PLAYING]: the engine
     * accepts data with no device attached, and that data goes nowhere. Anything
     * reporting USB output to the user must consult this.
     */
    fun isUsbOutputActive(): Boolean = nativeIsUsbOutputActive()

    /**
     * Whether a USB DAC is attached and could carry playback.
     *
     * Distinct from [isUsbOutputActive], which is only true once audio is
     * flowing. Use this to decide where to send the next track.
     */
    fun isUsbDeviceAttached(): Boolean = nativeIsUsbDeviceAttached()

    /** Name of the transport in use, for diagnostics. */
    fun getTransportName(): String = nativeGetTransportName()

    /** Bytes accepted by the USB transport. 0 when no hardware is attached. */
    fun getUsbBytesTransferred(): Long = nativeGetUsbBytesTransferred()

    /** Isochronous transfer errors reported by the kernel. */
    fun getUsbTransferErrors(): Long = nativeGetUsbTransferErrors()

    /**
     * Give native code a route back to `UsbDeviceConnection.controlTransfer`, so
     * UAC1/UAC2 sample-rate negotiation can run. Pass null to clear it.
     */
    fun setControlTransferBridge(bridge: UsbControlTransferBridge?): Boolean =
        nativeSetControlTransferBridge(bridge)

    /**
     * Implemented by the USB layer. The signature is matched by JNI reflection,
     * so the parameter list must stay in step with
     * `nativeSetControlTransferBridge` in native_bridge.cpp.
     */
    interface UsbControlTransferBridge {
        fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            buffer: ByteArray,
            length: Int,
            timeoutMs: Int
        ): Int
    }

    private external fun nativeAttachUsbDevice(
        fileDescriptor: Int,
        interfaceNumber: Int,
        altSetting: Int
    ): Boolean

    private external fun nativeDetachUsbDevice()
    private external fun nativeIsUsbOutputActive(): Boolean
    private external fun nativeIsUsbDeviceAttached(): Boolean
    private external fun nativeGetTransportName(): String
    private external fun nativeGetUsbBytesTransferred(): Long
    private external fun nativeGetUsbTransferErrors(): Long
    private external fun nativeSetControlTransferBridge(
        bridge: UsbControlTransferBridge?
    ): Boolean

    private external fun nativeOpenDecoder(path: String): Long
    private external fun nativeGetDecoderFormat(sessionId: Long): LongArray?
    private external fun nativeReadDecoder(
        sessionId: Long,
        output: ByteBuffer,
        maxFrames: Int
    ): Int
    private external fun nativeSeekDecoder(sessionId: Long, frameIndex: Long): Long
    private external fun nativeCloseDecoder(sessionId: Long)

    /**
     * Native callback registration for track transitions (gapless playback).
     * Called from native code when a gapless track transition occurs.
     */
    external fun registerTrackTransitionCallback(controller: Any): Boolean

    /**
     * Data class for detected audio format.
     */
    data class DetectedFormat(
        val sampleRate: Int,
        val nativeFormat: Int,
        val channels: Int
    )
}
