package com.bitperfect.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * UsbAudioManager - Android USB host API wrapper.
 *
 * Handles USB device enumeration, permission requests, connection management,
 * and passes raw descriptors to the native layer via JNI.
 */
class UsbAudioManager(
    private val context: Context,
    private val nativeEngine: com.bitperfect.android.engine.NativeAudioEngine =
        com.bitperfect.android.engine.NativeAudioEngine()
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

        val permissionIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION),
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
        if (rawDescriptors != null && rawDescriptors.isNotEmpty()) {
            val parsed = nativeEngine.parseDevice(rawDescriptors)
            if (parsed) {
                Log.i(TAG, "Device parsed successfully: ${device.deviceName}")
                return true
            } else {
                Log.e(TAG, "Failed to parse device descriptors")
                listener?.onError("Device is not a USB Audio Class device")
            }
        } else {
            Log.e(TAG, "No raw descriptors available")
            listener?.onError("Cannot read device descriptors")
        }

        return false
    }

    /**
     * Close the current USB connection.
     */
    fun closeDevice() {
        currentConnection?.close()
        currentConnection = null
        currentDevice = null
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
     * Register USB broadcast receiver.
     */
    fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    /**
     * Unregister USB broadcast receiver.
     */
    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered")
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

    private val usbReceiver = object : BroadcastReceiver() {
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
                        listener?.onDeviceDetached(it)
                        if (it == currentDevice) {
                            closeDevice()
                        }
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.let {
                        if (granted) {
                            listener?.onPermissionGranted(it)
                        } else {
                            listener?.onPermissionDenied(it)
                        }
                    }
                }
            }
        }
    }

    // Device descriptor parsing is handled via NativeAudioEngine.parseDevice()
}
