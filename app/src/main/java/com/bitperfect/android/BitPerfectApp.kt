package com.bitperfect.android

import android.app.Application
import android.util.Log

/**
 * BitPerfect Application class.
 * Initializes application-wide components and the native audio engine.
 */
class BitPerfectApp : Application() {

    companion object {
        private const val TAG = "BitPerfectApp"

        /**
         * Indicates whether the native library was successfully loaded.
         * When false, native audio features are unavailable but the app
         * can still display UI and non-native functionality.
         */
        var isNativeLoaded: Boolean = false
            private set

        init {
            try {
                System.loadLibrary("bitperfect_engine")
                isNativeLoaded = true
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isNativeLoaded = false
                Log.e(TAG, "Failed to load native library: ${e.message}")
            } catch (e: SecurityException) {
                isNativeLoaded = false
                Log.e(TAG, "Security exception loading native library: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BitPerfect application starting (native loaded: $isNativeLoaded)")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "BitPerfect application terminating")
    }
}
