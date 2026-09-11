package com.bitperfect.android.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat

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

        /**
         * A DAC is attached and access has not been granted yet — normally because
         * Android's app-chooser is on screen waiting to be answered. Reported so the
         * UI can say that, rather than leaving the DAC looking absent.
         */
        fun onPermissionPending(device: UsbDevice)
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
     * Start monitoring for USB device events with this class's own receiver.
     *
     * For an owner that has no USB receiver of its own. An owner that does — anything
     * holding a [UsbAudioManager], which must register one to hear the permission
     * broadcast — should instead call [scanForDevices] once and forward its receiver's
     * events to [onDeviceAttached] and [onDeviceDetached], rather than register a
     * second receiver for the same two actions.
     */
    fun startMonitoring() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Exported, because both actions are sent by the platform and a NOT_EXPORTED
        // receiver rejects broadcasts from other apps — the system included. Both are
        // protected broadcasts only the platform may send, so nothing is exposed.
        ContextCompat.registerReceiver(
            context,
            usbEventReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
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
     *
     * This is what covers the two cases that matter in practice: a DAC that was
     * plugged in before the app was launched, and a DAC whose arrival launched the
     * app through the "choose an app for this USB device" dialog. In the second case
     * the device is already in `UsbManager.deviceList` by the time the activity
     * exists, so nothing has to be dug out of the launch intent.
     */
    fun scanForDevices() {
        val devices = usbAudioManager.getConnectedAudioDevices()
        for (device in devices) {
            onDeviceAttached(device)
        }
    }

    /**
     * Report a device that the caller's own receiver saw attach.
     *
     * Exists so an owner that already registers a USB receiver — [UsbAudioManager]
     * must, for the permission broadcast — can drive this class without registering a
     * second receiver for the same two actions.
     *
     * A device that is already open is ignored, so a repeat ATTACHED broadcast, or a
     * [scanForDevices] call that overlaps one, cannot claim the same interface twice.
     */
    fun onDeviceAttached(device: UsbDevice) {
        val existing = connectedDevices[device.deviceName]
        if (existing != null && existing.isOpen) {
            Log.d(TAG, "Already open, ignoring repeat attach: ${device.deviceName}")
            return
        }
        handleDeviceAttached(device)
    }

    /** Report a device that the caller's own receiver saw detach. */
    fun onDeviceDetached(device: UsbDevice) {
        handleDeviceDetached(device)
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
     * Ask Android for access to an attached DAC that does not have it, at the user's
     * request. The fallback for a dismissed app-chooser; see [handleDeviceAttached]
     * for why nothing prompts automatically.
     *
     * @return true when a device needed access and a prompt was raised.
     */
    fun requestAccessForAttachedDevice(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbAudioManager.getConnectedAudioDevices()
            .firstOrNull { !usbManager.hasPermission(it) }
            ?: return false

        if (connectedDevices[device.deviceName] == null) {
            connectedDevices[device.deviceName] = DeviceState(device)
        }
        requestPermission(device)
        return true
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

    /**
     * Open every attached audio device that already has permission. Never prompts.
     *
     * This is how a grant made by Android's own "choose an app for the USB device"
     * dialog is noticed. That dialog *is* a permission grant when the app declares a
     * `USB_DEVICE_ATTACHED` intent filter, but it grants it silently: no
     * `ACTION_USB_PERMISSION` broadcast is sent, because the app never asked. So a
     * user who picked BitPerfect and tapped "Just once" left this class holding
     * `hasPermission = false` with nothing that would ever look again, and the DAC
     * was never opened — the app went on reporting "Android output" with permission
     * already in hand.
     *
     * Call it whenever the app comes back to the foreground and when the attach
     * intent is delivered; both happen right after that dialog is answered.
     */
    fun reconcile() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        for (device in usbAudioManager.getConnectedAudioDevices()) {
            val state = connectedDevices[device.deviceName]
            if (state != null && state.isOpen) continue
            if (!usbManager.hasPermission(device)) continue

            Log.i(TAG, "Permission already held, opening ${device.deviceName}")
            val tracked = state ?: DeviceState(device).also {
                connectedDevices[device.deviceName] = it
                listener?.onDeviceConnected(device)
            }
            tracked.hasPermission = true
            pendingPermissionDevice = null
            listener?.onPermissionGranted(device)
            openAndConfigureDevice(device)
        }
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        Log.i(TAG, "Device attached: ${device.deviceName} (${device.manufacturerName} ${device.productName})")

        val state = DeviceState(device)
        connectedDevices[device.deviceName] = state
        listener?.onDeviceConnected(device)

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            state.hasPermission = true
            listener?.onPermissionGranted(device)
            openAndConfigureDevice(device)
            return
        }

        // Deliberately does not prompt.
        //
        // Attaching a DAC makes Android show its own app-chooser, because this app
        // declares a USB_DEVICE_ATTACHED filter — and picking BitPerfect there grants
        // the permission. Asking as well put two dialogs on screen at the same moment,
        // one behind the other, each of which dismissed the other when answered. The
        // chooser is the grant; [reconcile] picks it up as soon as it is answered.
        //
        // If the chooser is dismissed, or the user picks the other app and changes
        // their mind, [requestPermission] is still there to be called deliberately —
        // the Audio info panel offers it — which is a prompt the user asked for rather
        // than one that arrived on top of another.
        Log.i(TAG, "No permission yet for ${device.deviceName}; waiting for the chooser")
        listener?.onPermissionPending(device)
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
