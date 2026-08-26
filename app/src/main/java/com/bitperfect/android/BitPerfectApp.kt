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

        init {
            System.loadLibrary("bitperfect_engine")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BitPerfect application starting")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "BitPerfect application terminating")
    }
}
