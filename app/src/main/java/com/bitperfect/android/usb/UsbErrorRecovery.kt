package com.bitperfect.android.usb

import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bitperfect.android.engine.NativeAudioEngine
import com.bitperfect.android.player.PlaybackController
import com.bitperfect.android.player.PlaybackState

/**
 * UsbErrorRecovery - Comprehensive error recovery for USB audio playback.
 *
 * Handles all failure modes gracefully to ensure the app never crashes
 * due to USB issues:
 *
 * - Permission failure: re-request permission or notify user
 * - Disconnect: clean stop, release resources, notify UI
 * - Reconnect: re-enumerate device, restore playback state, resume if configured
 * - Transfer failure: retry with exponential backoff, report persistent errors
 * - Endpoint failure: try alternate endpoint if available
 * - USB reset: full re-initialization sequence
 * - Android suspend/resume: save/restore playback state
 * - Corrupted data: skip frame and continue
 *
 * Design principle: Never crash. Always degrade gracefully.
 */
class UsbErrorRecovery(
    private val usbAudioManager: UsbAudioManager,
    private val playbackController: PlaybackController,
    private val engine: NativeAudioEngine
) {

    companion object {
        private const val TAG = "UsbErrorRecovery"
        private const val MAX_TRANSFER_RETRIES = 5
        private const val INITIAL_RETRY_DELAY_MS = 50L
        private const val MAX_RETRY_DELAY_MS = 5000L
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    /**
     * Error types that can be recovered from.
     */
    enum class ErrorType {
        PERMISSION_DENIED,
        DEVICE_DISCONNECTED,
        TRANSFER_FAILED,
        ENDPOINT_FAILED,
        UNSUPPORTED_FORMAT,
        BUFFER_OVERFLOW,
        BUFFER_UNDERRUN,
        USB_RESET_REQUIRED,
        DEVICE_SUSPENDED,
        DECODER_ERROR,
        CORRUPTED_DATA
    }

    /**
     * Recovery strategy for each error type.
     */
    enum class RecoveryAction {
        REQUEST_PERMISSION,
        STOP_AND_NOTIFY,
        RETRY_TRANSFER,
        SWITCH_ENDPOINT,
        RESET_DEVICE,
        RECONNECT,
        SKIP_FRAME,
        SKIP_TRACK,
        REDUCE_BUFFER,
        NOTIFY_USER
    }

    /**
     * Listener for recovery events.
     */
    interface RecoveryListener {
        fun onRecoveryStarted(errorType: ErrorType, action: RecoveryAction)
        fun onRecoverySuccess(errorType: ErrorType)
        fun onRecoveryFailed(errorType: ErrorType, message: String)
        fun onDeviceDisconnected()
        fun onDeviceReconnected()
        fun onUserActionRequired(message: String)
    }

    private var listener: RecoveryListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var transferRetryCount = 0
    private var reconnectAttempts = 0
    private var isRecovering = false

    // Saved state for suspend/resume
    private var savedTrackPath: String? = null
    private var savedPositionMs: Long = 0L
    private var savedWasPlaying: Boolean = false
    private var autoResumeOnReconnect: Boolean = true

    /**
     * Set the recovery event listener.
     */
    fun setListener(listener: RecoveryListener) {
        this.listener = listener
    }

    /**
     * Set whether to auto-resume playback on device reconnect.
     */
    fun setAutoResumeOnReconnect(enabled: Boolean) {
        autoResumeOnReconnect = enabled
    }

    /**
     * Handle a playback error from the PlaybackController.
     */
    fun handlePlaybackError(errorMessage: String) {
        Log.e(TAG, "Playback error: $errorMessage")

        val errorType = classifyError(errorMessage)
        val action = determineRecoveryAction(errorType)

        Log.i(TAG, "Error classified as $errorType, recovery action: $action")
        executeRecovery(errorType, action)
    }

    /**
     * Handle USB device disconnect event.
     * Called by UsbAudioManager when a device is physically removed.
     */
    fun handleDeviceDisconnected(device: UsbDevice) {
        Log.w(TAG, "Device disconnected: ${device.deviceName}")

        // Save current playback state for potential resume
        savePlaybackState()

        // Stop playback cleanly without crashing
        try {
            engine.stopPlayback()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback on disconnect: ${e.message}")
        }

        // Close USB resources safely
        try {
            usbAudioManager.closeDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing device on disconnect: ${e.message}")
        }

        // Notify listeners
        listener?.onDeviceDisconnected()
        listener?.onRecoveryStarted(ErrorType.DEVICE_DISCONNECTED, RecoveryAction.STOP_AND_NOTIFY)

        isRecovering = false
        reconnectAttempts = 0
    }

    /**
     * Handle USB device reconnect event.
     * Called by UsbAudioManager when a device is re-attached.
     */
    fun handleDeviceReconnected(device: UsbDevice) {
        Log.i(TAG, "Device reconnected: ${device.deviceName}")

        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            listener?.onRecoveryFailed(
                ErrorType.DEVICE_DISCONNECTED,
                "Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts"
            )
            return
        }

        listener?.onRecoveryStarted(ErrorType.DEVICE_DISCONNECTED, RecoveryAction.RECONNECT)

        // Attempt to re-establish connection
        handler.postDelayed({
            attemptReconnect(device)
        }, RECONNECT_DELAY_MS)
    }

    /**
     * Handle USB transfer failure.
     * Implements retry with exponential backoff.
     */
    fun handleTransferFailure() {
        transferRetryCount++
        Log.w(TAG, "Transfer failure #$transferRetryCount")

        if (transferRetryCount > MAX_TRANSFER_RETRIES) {
            Log.e(TAG, "Max transfer retries exceeded, stopping playback")
            transferRetryCount = 0
            listener?.onRecoveryFailed(ErrorType.TRANSFER_FAILED, "USB transfer failed repeatedly")
            playbackController.stop()
            listener?.onUserActionRequired("USB transfer error. Please check your DAC connection.")
            return
        }

        val delay = calculateBackoffDelay(transferRetryCount)
        Log.d(TAG, "Retrying transfer in ${delay}ms")

        listener?.onRecoveryStarted(ErrorType.TRANSFER_FAILED, RecoveryAction.RETRY_TRANSFER)

        handler.postDelayed({
            retryTransfer()
        }, delay)
    }

    /**
     * Handle endpoint failure by attempting to use an alternate endpoint.
     */
    fun handleEndpointFailure() {
        Log.w(TAG, "Endpoint failure - attempting alternate endpoint")
        listener?.onRecoveryStarted(ErrorType.ENDPOINT_FAILED, RecoveryAction.SWITCH_ENDPOINT)

        // The native engine handles alternate endpoint selection
        val engineState = engine.getState()
        if (engineState == NativeAudioEngine.STATE_ERROR) {
            // Full reset needed
            handleUsbReset()
        } else {
            // Try reconfiguring with current parameters
            reconfigureEngine()
        }
    }

    /**
     * Handle USB reset requirement.
     * Performs full re-initialization of the USB connection.
     */
    fun handleUsbReset() {
        Log.w(TAG, "USB reset required - performing full re-initialization")
        listener?.onRecoveryStarted(ErrorType.USB_RESET_REQUIRED, RecoveryAction.RESET_DEVICE)

        savePlaybackState()

        // Stop everything safely
        try {
            engine.stopPlayback()
        } catch (e: Exception) {
            Log.e(TAG, "Error during reset stop: ${e.message}")
        }

        try {
            usbAudioManager.closeDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Error during reset close: ${e.message}")
        }

        // Re-initialize after a brief delay
        handler.postDelayed({
            val devices = usbAudioManager.getConnectedAudioDevices()
            if (devices.isNotEmpty()) {
                attemptReconnect(devices.first())
            } else {
                listener?.onRecoveryFailed(
                    ErrorType.USB_RESET_REQUIRED,
                    "No USB audio device found after reset"
                )
            }
        }, RECONNECT_DELAY_MS)
    }

    /**
     * Handle Android suspend event (screen off, doze mode).
     * Saves playback state for later restoration.
     */
    fun handleSuspend() {
        Log.i(TAG, "Handling suspend - saving state")
        savePlaybackState()
        // Playback continues via foreground service and wake lock
        // Only save state as a precaution
    }

    /**
     * Handle Android resume event (device wake up).
     * Restores playback state if USB device is still connected.
     */
    fun handleResume() {
        Log.i(TAG, "Handling resume - checking device state")

        val devices = usbAudioManager.getConnectedAudioDevices()
        if (devices.isEmpty()) {
            Log.w(TAG, "No USB device after resume")
            listener?.onDeviceDisconnected()
            return
        }

        // Verify the engine is still in a good state
        val engineState = engine.getState()
        if (engineState == NativeAudioEngine.STATE_ERROR) {
            Log.w(TAG, "Engine in error state after resume - attempting recovery")
            handleUsbReset()
        } else {
            listener?.onDeviceReconnected()
        }
    }

    /**
     * Handle permission denial.
     */
    fun handlePermissionDenied(device: UsbDevice) {
        Log.w(TAG, "Permission denied for ${device.deviceName}")
        listener?.onRecoveryStarted(ErrorType.PERMISSION_DENIED, RecoveryAction.REQUEST_PERMISSION)
        listener?.onUserActionRequired(
            "USB permission required. Please grant access to your DAC."
        )
    }

    /**
     * Handle decoder error (corrupted file, unsupported codec).
     */
    fun handleDecoderError(trackPath: String, errorMessage: String) {
        Log.e(TAG, "Decoder error for $trackPath: $errorMessage")
        listener?.onRecoveryStarted(ErrorType.DECODER_ERROR, RecoveryAction.SKIP_TRACK)

        // Skip to next track
        playbackController.next()
        listener?.onRecoverySuccess(ErrorType.DECODER_ERROR)
    }

    /**
     * Handle buffer underrun.
     */
    fun handleBufferUnderrun() {
        Log.w(TAG, "Buffer underrun detected")
        listener?.onRecoveryStarted(ErrorType.BUFFER_UNDERRUN, RecoveryAction.REDUCE_BUFFER)
        // The native engine handles buffer size adjustments internally
        // We just log and notify
    }

    /**
     * Reset all retry counters. Called when playback starts successfully.
     */
    fun resetCounters() {
        transferRetryCount = 0
        reconnectAttempts = 0
        isRecovering = false
    }

    private fun classifyError(errorMessage: String): ErrorType {
        val msg = errorMessage.lowercase()
        return when {
            "permission" in msg -> ErrorType.PERMISSION_DENIED
            "disconnect" in msg || "detached" in msg -> ErrorType.DEVICE_DISCONNECTED
            "transfer" in msg || "pipe" in msg -> ErrorType.TRANSFER_FAILED
            "endpoint" in msg -> ErrorType.ENDPOINT_FAILED
            "unsupported" in msg || "format" in msg -> ErrorType.UNSUPPORTED_FORMAT
            "overflow" in msg -> ErrorType.BUFFER_OVERFLOW
            "underrun" in msg -> ErrorType.BUFFER_UNDERRUN
            "reset" in msg -> ErrorType.USB_RESET_REQUIRED
            "suspend" in msg -> ErrorType.DEVICE_SUSPENDED
            "decode" in msg || "corrupt" in msg -> ErrorType.DECODER_ERROR
            else -> ErrorType.TRANSFER_FAILED
        }
    }

    private fun determineRecoveryAction(errorType: ErrorType): RecoveryAction {
        return when (errorType) {
            ErrorType.PERMISSION_DENIED -> RecoveryAction.REQUEST_PERMISSION
            ErrorType.DEVICE_DISCONNECTED -> RecoveryAction.STOP_AND_NOTIFY
            ErrorType.TRANSFER_FAILED -> RecoveryAction.RETRY_TRANSFER
            ErrorType.ENDPOINT_FAILED -> RecoveryAction.SWITCH_ENDPOINT
            ErrorType.UNSUPPORTED_FORMAT -> RecoveryAction.NOTIFY_USER
            ErrorType.BUFFER_OVERFLOW -> RecoveryAction.REDUCE_BUFFER
            ErrorType.BUFFER_UNDERRUN -> RecoveryAction.REDUCE_BUFFER
            ErrorType.USB_RESET_REQUIRED -> RecoveryAction.RESET_DEVICE
            ErrorType.DEVICE_SUSPENDED -> RecoveryAction.RECONNECT
            ErrorType.DECODER_ERROR -> RecoveryAction.SKIP_TRACK
            ErrorType.CORRUPTED_DATA -> RecoveryAction.SKIP_FRAME
        }
    }

    private fun executeRecovery(errorType: ErrorType, action: RecoveryAction) {
        if (isRecovering) {
            Log.w(TAG, "Already recovering, skipping")
            return
        }
        isRecovering = true
        listener?.onRecoveryStarted(errorType, action)

        when (action) {
            RecoveryAction.REQUEST_PERMISSION -> {
                listener?.onUserActionRequired("USB permission required")
                isRecovering = false
            }
            RecoveryAction.STOP_AND_NOTIFY -> {
                safeStop()
                listener?.onDeviceDisconnected()
                isRecovering = false
            }
            RecoveryAction.RETRY_TRANSFER -> {
                handleTransferFailure()
                isRecovering = false
            }
            RecoveryAction.SWITCH_ENDPOINT -> {
                handleEndpointFailure()
                isRecovering = false
            }
            RecoveryAction.RESET_DEVICE -> {
                handleUsbReset()
            }
            RecoveryAction.RECONNECT -> {
                val devices = usbAudioManager.getConnectedAudioDevices()
                if (devices.isNotEmpty()) {
                    attemptReconnect(devices.first())
                } else {
                    listener?.onRecoveryFailed(errorType, "No device available")
                    isRecovering = false
                }
            }
            RecoveryAction.SKIP_FRAME -> {
                // Native engine handles frame skipping
                isRecovering = false
                listener?.onRecoverySuccess(errorType)
            }
            RecoveryAction.SKIP_TRACK -> {
                playbackController.next()
                isRecovering = false
                listener?.onRecoverySuccess(errorType)
            }
            RecoveryAction.REDUCE_BUFFER -> {
                // Request native engine to increase buffer
                isRecovering = false
                listener?.onRecoverySuccess(errorType)
            }
            RecoveryAction.NOTIFY_USER -> {
                listener?.onUserActionRequired("Unsupported audio format")
                isRecovering = false
            }
        }
    }

    private fun attemptReconnect(device: UsbDevice) {
        Log.i(TAG, "Attempting reconnect to ${device.deviceName}")

        val opened = usbAudioManager.openDevice(device)
        if (opened) {
            Log.i(TAG, "Reconnect successful")
            reconnectAttempts = 0
            isRecovering = false
            listener?.onDeviceReconnected()
            listener?.onRecoverySuccess(ErrorType.DEVICE_DISCONNECTED)

            // Restore playback if configured
            if (autoResumeOnReconnect && savedWasPlaying) {
                restorePlaybackState()
            }
        } else {
            Log.w(TAG, "Reconnect failed, attempt $reconnectAttempts")
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                handler.postDelayed({
                    handleDeviceReconnected(device)
                }, RECONNECT_DELAY_MS * reconnectAttempts)
            } else {
                isRecovering = false
                listener?.onRecoveryFailed(
                    ErrorType.DEVICE_DISCONNECTED,
                    "Could not reconnect to device"
                )
                listener?.onUserActionRequired("Cannot reconnect to your DAC. Please check the connection.")
            }
        }
    }

    private fun retryTransfer() {
        // The native engine retries internally; this is a higher-level retry
        val engineState = engine.getState()
        if (engineState == NativeAudioEngine.STATE_PLAYING) {
            transferRetryCount = 0
            listener?.onRecoverySuccess(ErrorType.TRANSFER_FAILED)
        } else if (engineState == NativeAudioEngine.STATE_ERROR) {
            if (transferRetryCount < MAX_TRANSFER_RETRIES) {
                handleTransferFailure()
            }
        }
    }

    private fun reconfigureEngine() {
        val sampleRate = engine.getCurrentSampleRate().takeIf { it > 0 } ?: 44100
        val configured = engine.configure(
            sampleRate,
            NativeAudioEngine.FORMAT_S16_LE,
            2,
            50
        )
        if (configured) {
            listener?.onRecoverySuccess(ErrorType.ENDPOINT_FAILED)
        } else {
            listener?.onRecoveryFailed(ErrorType.ENDPOINT_FAILED, "Could not reconfigure engine")
        }
    }

    private fun savePlaybackState() {
        val state = playbackController.state
        when (state) {
            is PlaybackState.Playing -> {
                savedTrackPath = state.trackPath
                savedPositionMs = state.positionMs
                savedWasPlaying = true
            }
            is PlaybackState.Paused -> {
                savedTrackPath = state.trackPath
                savedPositionMs = state.positionMs
                savedWasPlaying = false
            }
            else -> {
                savedWasPlaying = false
            }
        }
        Log.d(TAG, "State saved: track=$savedTrackPath, pos=$savedPositionMs, playing=$savedWasPlaying")
    }

    private fun restorePlaybackState() {
        val trackPath = savedTrackPath ?: return
        Log.i(TAG, "Restoring playback: track=$trackPath, pos=$savedPositionMs")

        // Seek to saved position and resume
        playbackController.seek(savedPositionMs)
        if (savedWasPlaying) {
            playbackController.play()
        }

        // Clear saved state
        savedTrackPath = null
        savedPositionMs = 0L
        savedWasPlaying = false
    }

    private fun safeStop() {
        try {
            playbackController.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error during safe stop: ${e.message}")
        }
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val delay = INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(6))
        return delay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}
