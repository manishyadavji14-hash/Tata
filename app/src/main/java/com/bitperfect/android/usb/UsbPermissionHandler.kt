package com.bitperfect.android.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * UsbPermissionHandler - Manages USB device permission lifecycle.
 *
 * Responsibilities:
 * - Registers BroadcastReceiver for USB_DEVICE_ATTACHED and USB_DEVICE_DETACHED
 * - Manages permission request dialog flow
 * - Tracks grant/deny state for connected devices
 * - Maintains device connection state
 * - Provides callbacks for permission events
 * - Handles edge cases (device removed during permission dialog, etc.)
 */
class UsbPermissionHandler(
    private val context: Context,
    private val usbAudioManager: UsbAudioManager
) {

    companion object {
        private const val TAG = "UsbPermissionHandler"
    }

    /**
     * Listener for USB permission and connection events.
     */
    interface PermissionListener {
        fun onDeviceConnected(device: UsbDevice)
        fun onDeviceDisconnected(device: UsbDevice)
        fun onPermissionGranted(device: UsbDevice)
        fun onPermissionDenied(device: UsbDevice)
        fun onDeviceReady(device: UsbDevice)
        fun onDeviceError(device: UsbDevice, error: String)
    }

    private var listener: PermissionListener? = null
    private val connectedDevices = mutableMapOf<String, DeviceState>()
    private var pendingPermissionDevice: UsbDevice? = null

    /**
     * State tracking for a connected USB device.
     */
    data class DeviceState(
        val device: UsbDevice,
        var hasPermission: Boolean = false,
        var isOpen: Boolean = false,
        var isConfigured: Boolean = false,
        var permissionAttempts: Int = 0
    )

    /**
     * Set the permission event listener.
     */
    fun setListener(listener: PermissionListener) {
        this.listener = listener
    }

    /**
     * Start monitoring for USB device events.
     */
    fun startMonitoring() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbEventReceiver, filter)
        }
        Log.i(TAG, "USB permission handler monitoring started")

        // Check for already connected devices
        scanForDevices()
    }

    /**
     * Stop monitoring USB device events.
     */
    fun stopMonitoring() {
        try {
            context.unregisterReceiver(usbEventReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "USB receiver not registered")
        }
    }

    /**
     * Scan for already-connected USB audio devices.
     */
    fun scanForDevices() {
        val devices = usbAudioManager.getConnectedAudioDevices()
        for (device in devices) {
            handleDeviceAttached(device)
        }
    }

    /**
     * Request permission for a specific device.
     * Tracks attempts to avoid infinite permission loops.
     */
    fun requestPermission(device: UsbDevice) {
        val state = connectedDevices[device.deviceName]
        if (state != null && state.permissionAttempts >= 3) {
            Log.w(TAG, "Max permission attempts reached for ${device.deviceName}")
            listener?.onDeviceError(device, "Permission denied after multiple attempts")
            return
        }

        state?.let { it.permissionAttempts++ }
        pendingPermissionDevice = device
        usbAudioManager.requestPermission(device)
        Log.d(TAG, "Permission requested for ${device.deviceName}")
    }

    /**
     * Handle permission being granted for a device.
     */
    fun onPermissionGranted(device: UsbDevice) {
        val state = connectedDevices[device.deviceName] ?: return
        state.hasPermission = true
        pendingPermissionDevice = null
        Log.i(TAG, "Permission granted for ${device.deviceName}")
        listener?.onPermissionGranted(device)

        // Attempt to open and configure the device
        openAndConfigureDevice(device)
    }

    /**
     * Handle permission being denied for a device.
     */
    fun onPermissionDenied(device: UsbDevice) {
        pendingPermissionDevice = null
        Log.w(TAG, "Permission denied for ${device.deviceName}")
        listener?.onPermissionDenied(device)
    }

    /**
     * Get the current state of a connected device.
     */
    fun getDeviceState(deviceName: String): DeviceState? = connectedDevices[deviceName]

    /**
     * Get all currently connected devices.
     */
    fun getConnectedDevices(): List<DeviceState> = connectedDevices.values.toList()

    /**
     * Check if a device is currently connected and has permission.
     */
    fun isDeviceReady(deviceName: String): Boolean {
        val state = connectedDevices[deviceName] ?: return false
        return state.hasPermission && state.isOpen && state.isConfigured
    }

    /**
     * Check if any audio device is currently ready.
     */
    fun hasReadyDevice(): Boolean = connectedDevices.values.any {
        it.hasPermission && it.isOpen && it.isConfigured
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        Log.i(TAG, "Device attached: ${device.deviceName} (${device.manufacturerName} ${device.productName})")

        val state = DeviceState(device)
        connectedDevices[device.deviceName] = state
        listener?.onDeviceConnected(device)

        // Check if we already have permission
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            state.hasPermission = true
            listener?.onPermissionGranted(device)
            openAndConfigureDevice(device)
        } else {
            requestPermission(device)
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        Log.i(TAG, "Device detached: ${device.deviceName}")

        connectedDevices.remove(device.deviceName)
        listener?.onDeviceDisconnected(device)

        // If this was the device we were requesting permission for, clear the pending state
        if (pendingPermissionDevice?.deviceName == device.deviceName) {
            pendingPermissionDevice = null
        }
    }

    private fun openAndConfigureDevice(device: UsbDevice) {
        val state = connectedDevices[device.deviceName] ?: return

        val success = usbAudioManager.openDevice(device)
        if (success) {
            state.isOpen = true
            state.isConfigured = true
            Log.i(TAG, "Device opened and configured: ${device.deviceName}")
            listener?.onDeviceReady(device)
        } else {
            state.isOpen = false
            state.isConfigured = false
            Log.e(TAG, "Failed to open device: ${device.deviceName}")
            listener?.onDeviceError(device, "Failed to open USB device")
        }
    }

    private val usbEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { handleDeviceAttached(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { handleDeviceDetached(it) }
                }
            }
        }
    }
}
