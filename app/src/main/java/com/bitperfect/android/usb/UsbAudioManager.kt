package com.bitperfect.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitperfect.android.engine.NativeAudioEngine

/**
 * UsbAudioManager - Android USB host API wrapper.
 *
 * Handles USB device enumeration, permission requests, connection management,
 * and passes raw descriptors to the native layer via JNI.
 */
class UsbAudioManager(
    private val context: Context,
    private val nativeEngine: NativeAudioEngine
) {

    companion object {
        private const val TAG = "UsbAudioManager"
        private const val ACTION_USB_PERMISSION = "com.bitperfect.android.USB_PERMISSION"
        private const val USB_AUDIO_CLASS = 1
        private const val USB_AUDIO_STREAMING_SUBCLASS = 2
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var currentConnection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var listener: UsbAudioListener? = null

    /** Interface claimed for streaming, released on close. */
    private var claimedInterface: UsbInterface? = null

    /** Guards against a second registration, which would double every callback. */
    private var receiversRegistered = false

    /**
     * Listener for USB audio events.
     */
    interface UsbAudioListener {
        fun onDeviceAttached(device: UsbDevice)
        fun onDeviceDetached(device: UsbDevice)
        fun onPermissionGranted(device: UsbDevice)
        fun onPermissionDenied(device: UsbDevice)
        fun onDeviceConfigured(deviceName: String, sampleRates: IntArray, bitDepths: IntArray)
        fun onError(message: String)
    }

    /**
     * Set the event listener.
     */
    fun setListener(listener: UsbAudioListener) {
        this.listener = listener
    }

    /**
     * Get list of connected USB audio devices.
     */
    fun getConnectedAudioDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.filter { device ->
            isUsbAudioDevice(device)
        }
    }

    /**
     * Request permission to access a USB device.
     */
    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            listener?.onPermissionGranted(device)
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

        // The package has to be set. FLAG_MUTABLE is required — the system fills
        // EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED into this intent before sending
        // it — but since Android 14 a mutable PendingIntent carrying an implicit
        // intent throws IllegalArgumentException. Unqualified, this line took down
        // the permission request the moment a DAC arrived without prior consent,
        // from inside a BroadcastReceiver, where it becomes a crash.
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Open a connection to the USB device and parse its descriptors.
     * @return true if device was successfully opened and parsed
     */
    fun openDevice(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "No permission for device: ${device.deviceName}")
            listener?.onError("No USB permission")
            return false
        }

        val connection = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open device: ${device.deviceName}")
            listener?.onError("Failed to open USB device")
            return false
        }

        currentDevice = device
        currentConnection = connection

        // Get raw descriptors and pass to native layer via NativeAudioEngine
        val rawDescriptors = connection.rawDescriptors
        if (rawDescriptors == null || rawDescriptors.isEmpty()) {
            Log.e(TAG, "No raw descriptors available")
            listener?.onError("Cannot read device descriptors")
            return false
        }

        if (!nativeEngine.parseDevice(rawDescriptors)) {
            Log.e(TAG, "Failed to parse device descriptors")
            listener?.onError("Device is not a USB Audio Class device")
            return false
        }

        Log.i(TAG, "Device parsed successfully: ${device.deviceName}")

        // Descriptors alone only describe the device. To actually stream, the
        // streaming interface has to be claimed away from the kernel driver and
        // the file descriptor handed to the native transport.
        if (!claimStreamingInterface(device, connection)) {
            // Parsing succeeded, so the device is still usable for reporting its
            // capabilities; it just cannot be an output yet.
            Log.w(TAG, "Descriptors parsed but no streaming interface could be claimed")
            return false
        }

        // Let native perform UAC rate negotiation through this connection.
        nativeEngine.setControlTransferBridge(controlTransferBridge)

        return true
    }

    /**
     * Claim the audio streaming interface and hand the descriptor to native.
     *
     * Alternate setting 0 of a UAC streaming interface is the zero-bandwidth
     * setting, so a non-zero alternate setting carrying an isochronous OUT
     * endpoint is what playback needs. Picking the exact setting per sample rate
     * is the native engine's job; here we only need the interface claimed and a
     * usable setting selected so a descriptor exists to submit against.
     */
    private fun claimStreamingInterface(
        device: UsbDevice,
        connection: UsbDeviceConnection
    ): Boolean {
        val candidate = findOutputStreamingInterface(device)
        if (candidate == null) {
            Log.e(TAG, "No isochronous OUT streaming interface on ${device.deviceName}")
            listener?.onError("Device has no audio output endpoint")
            return false
        }

        val (usbInterface, altSetting) = candidate

        // force = true takes the interface from the kernel's usbaudio driver,
        // which will otherwise hold it and make exclusive access impossible.
        if (!connection.claimInterface(usbInterface, true)) {
            Log.e(TAG, "Could not claim interface ${usbInterface.id}")
            listener?.onError("Another driver is holding the audio interface")
            return false
        }
        claimedInterface = usbInterface

        if (altSetting != 0 && !connection.setInterface(usbInterface)) {
            Log.w(TAG, "setInterface failed for alt setting $altSetting")
        }

        // Give the engine a way back here. Which alternate setting is active
        // decides whether the kernel accepts the URBs at all, the engine is what
        // knows which one the rate and bit depth need, and this class is the only
        // thing holding the connection that can change it.
        nativeEngine.setAltSettingSelector(altSettingSelector)

        val attached = nativeEngine.attachUsbDevice(
            fileDescriptor = connection.fileDescriptor,
            interfaceNumber = usbInterface.id,
            altSetting = altSetting
        )
        if (!attached) {
            Log.e(TAG, "Native layer rejected the USB file descriptor")
            listener?.onError("Could not attach the audio device")
            connection.releaseInterface(usbInterface)
            claimedInterface = null
            return false
        }

        Log.i(
            TAG,
            "USB output attached: interface=${usbInterface.id} alt=$altSetting " +
                "fd=${connection.fileDescriptor}"
        )
        return true
    }

    /**
     * Find an audio streaming interface that carries an isochronous OUT endpoint,
     * returning it with its alternate setting number.
     */
    private fun findOutputStreamingInterface(device: UsbDevice): Pair<UsbInterface, Int>? {
        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            if (candidate.interfaceClass != USB_AUDIO_CLASS) continue
            if (candidate.interfaceSubclass != USB_AUDIO_STREAMING_SUBCLASS) continue

            for (e in 0 until candidate.endpointCount) {
                val endpoint = candidate.getEndpoint(e)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue
                if (endpoint.direction != UsbConstants.USB_DIR_OUT) continue
                return candidate to candidate.alternateSetting
            }
        }
        return null
    }

    /**
     * Close the current USB connection.
     */
    fun closeDevice() {
        // Detach native first: its reaper thread submits against this
        // descriptor, so the connection must outlive the transport.
        nativeEngine.detachUsbDevice()
        nativeEngine.setControlTransferBridge(null)
        nativeEngine.setAltSettingSelector(null)

        claimedInterface?.let { claimed ->
            try {
                currentConnection?.releaseInterface(claimed)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Interface already released: ${e.message}")
            }
        }
        claimedInterface = null

        currentConnection?.close()
        currentConnection = null
        currentDevice = null
    }

    /**
     * Makes an alternate setting the active one on the claimed interface.
     *
     * Android exposes every alternate setting of an interface as its own
     * [UsbInterface] with the same id, which is what makes this selectable at all:
     * `setInterface` picks between them, and it is the call that reaches the
     * kernel's `USBDEVFS_SETINTERFACE`, so the kernel's idea of what is active
     * stays in step. Sending a raw SET_INTERFACE control transfer instead would
     * change the device and not the kernel, and the URBs would still be rejected.
     */
    private val altSettingSelector =
        NativeAudioEngine.UsbAltSettingSelector { interfaceNumber, altSetting ->
            val connection = currentConnection
            val device = currentDevice
            if (connection == null || device == null) {
                Log.w(TAG, "No open connection to select alt setting $altSetting on")
                return@UsbAltSettingSelector false
            }

            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                if (candidate.id != interfaceNumber) continue
                if (candidate.alternateSetting != altSetting) continue

                // claimedInterface is deliberately left alone: it is what
                // closeDevice releases, and it must stay the exact object that was
                // claimed rather than a sibling alternate setting.
                val selected = connection.setInterface(candidate)
                if (selected) {
                    Log.i(TAG, "Alt setting $altSetting active on interface $interfaceNumber")
                } else {
                    Log.e(TAG, "Device refused alt setting $altSetting")
                }
                return@UsbAltSettingSelector selected
            }

            Log.e(
                TAG,
                "Interface $interfaceNumber has no alternate setting $altSetting"
            )
            false
        }

    /**
     * Routes native control-transfer requests to the open connection.
     *
     * UsbControl's UAC1/UAC2 rate-setting logic already existed but could never
     * run, because nothing installed a transfer function on the native side.
     */
    private val controlTransferBridge =
        object : NativeAudioEngine.UsbControlTransferBridge {
            override fun controlTransfer(
                requestType: Int,
                request: Int,
                value: Int,
                index: Int,
                buffer: ByteArray,
                length: Int,
                timeoutMs: Int
            ): Int = this@UsbAudioManager.controlTransfer(
                requestType, request, value, index, buffer, length, timeoutMs
            )
        }

    /**
     * Perform a USB control transfer.
     */
    fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeout: Int
    ): Int {
        val connection = currentConnection ?: return -1
        return connection.controlTransfer(requestType, request, value, index, buffer, length, timeout)
    }

    /**
     * Register the USB broadcast receivers. Safe to call more than once.
     *
     * Two registrations, because the two groups of actions have opposite senders and
     * therefore need opposite export flags.
     *
     * ATTACHED and DETACHED are sent by the system, and a receiver registered
     * NOT_EXPORTED does not accept broadcasts from another app — including the
     * platform. Both are protected broadcasts that only the platform may send, so
     * exporting them gives nothing away. The permission result, by contrast, comes
     * from this app's own PendingIntent, so that receiver stays unexported and no
     * other app can forge a granted permission.
     *
     * All three actions used to share one NOT_EXPORTED registration: the right flag
     * for the permission action, the wrong one for the device events, which is why a
     * DAC attached while the app was already running could go unnoticed.
     */
    fun registerReceiver() {
        if (receiversRegistered) return
        receiversRegistered = true

        ContextCompat.registerReceiver(
            context,
            deviceEventReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Unregister the USB broadcast receivers.
     */
    fun unregisterReceiver() {
        receiversRegistered = false
        for (receiver in listOf(deviceEventReceiver, permissionReceiver)) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered")
            }
        }
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_AUDIO_CLASS) {
                return true
            }
        }
        return false
    }

    /** Exported: these two arrive from the platform. See [registerReceiver]. */
    private val deviceEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        if (isUsbAudioDevice(it)) {
                            Log.i(TAG, "USB audio device attached: ${it.deviceName}")
                            listener?.onDeviceAttached(it)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        Log.i(TAG, "USB audio device detached: ${it.deviceName}")
                        // Detached before the listener is told, so anything the
                        // listener then reads about the engine is already true.
                        if (it == currentDevice) {
                            closeDevice()
                        }
                        listener?.onDeviceDetached(it)
                    }
                }
            }
        }
    }

    /** Unexported: this one is this app's own PendingIntent. See [registerReceiver]. */
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                listener?.onPermissionGranted(device)
            } else {
                listener?.onPermissionDenied(device)
            }
        }
    }

    // Device descriptor parsing is handled via NativeAudioEngine.parseDevice()
}
