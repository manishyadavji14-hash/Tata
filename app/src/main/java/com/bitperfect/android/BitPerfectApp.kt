package com.bitperfect.android

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * BitPerfect Application class.
 * Initializes application-wide components and the native audio engine.
 *
 * Installs an uncaught exception handler that writes crash reports to the
 * app's external files directory so they can be inspected on devices where
 * logcat is not accessible (e.g. Android 16 restrictions).
 *
 * Crash report path (readable without root):
 *   /storage/emulated/0/Android/data/com.bitperfect.android/files/crash.txt
 * Startup log path:
 *   /storage/emulated/0/Android/data/com.bitperfect.android/files/startup.txt
 */
class BitPerfectApp : Application() {

    companion object {
        private const val TAG = "BitPerfectApp"

        private const val CRASH_FILE_NAME = "crash.txt"
        private const val STARTUP_FILE_NAME = "startup.txt"

        /**
         * Indicates whether the native library was successfully loaded.
         * When false, native audio features are unavailable but the app
         * can still display UI and non-native functionality.
         */
        var isNativeLoaded: Boolean = false
            private set

        /**
         * Detail about the native library load result. Empty when the load
         * succeeded, otherwise contains the failure reason.
         */
        var nativeLoadError: String = ""
            private set

        init {
            try {
                System.loadLibrary("bitperfect_engine")
                isNativeLoaded = true
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isNativeLoaded = false
                nativeLoadError = "UnsatisfiedLinkError: ${e.message}"
                Log.e(TAG, "Failed to load native library: ${e.message}")
            } catch (e: SecurityException) {
                isNativeLoaded = false
                nativeLoadError = "SecurityException: ${e.message}"
                Log.e(TAG, "Security exception loading native library: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        // Install the crash handler as the very first action so that any
        // failure during the rest of startup is captured to disk.
        installCrashHandler()

        super.onCreate()

        writeStartupLog()
        Log.i(TAG, "BitPerfect application starting (native loaded: $isNativeLoaded)")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "BitPerfect application terminating")
    }

    /**
     * Installs a default uncaught exception handler that persists the full
     * stack trace (including the cause chain) to the app's external files
     * directory, then delegates to the previously installed handler so the
     * process still terminates normally.
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildCrashReport(thread, throwable)
                writeReportFile(CRASH_FILE_NAME, report)
            } catch (e: Throwable) {
                // The crash handler must never crash. Swallow everything.
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Builds the crash report text: environment details followed by the
     * exception stack trace and the full cause chain.
     */
    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        val out = PrintWriter(writer)

        out.println("=== BitPerfect Crash Report ===")
        out.println("Time: ${Date()}")
        out.println("Thread: ${thread.name}")
        out.println("Android: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
        out.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        out.println("Native loaded: $isNativeLoaded")
        if (nativeLoadError.isNotEmpty()) {
            out.println("Native load error: $nativeLoadError")
        }
        out.println()

        out.println("=== Exception ===")
        out.println("Class: ${throwable.javaClass.name}")
        out.println("Message: ${throwable.message}")
        out.println()
        throwable.printStackTrace(out)

        var cause = throwable.cause
        var depth = 1
        while (cause != null && depth <= 10) {
            out.println()
            out.println("=== Caused by (level $depth) ===")
            out.println("Class: ${cause.javaClass.name}")
            out.println("Message: ${cause.message}")
            out.println()
            cause.printStackTrace(out)

            val next = cause.cause
            if (next === cause) {
                break
            }
            cause = next
            depth++
        }

        out.flush()
        return writer.toString()
    }

    /**
     * Writes a startup log on every launch so we can confirm the app reached
     * Application.onCreate() and see the native library status.
     */
    private fun writeStartupLog() {
        try {
            val builder = StringBuilder()
            builder.appendLine("=== BitPerfect Startup Log ===")
            builder.appendLine("Time: ${Date()}")
            builder.appendLine("Native loaded: $isNativeLoaded")
            if (nativeLoadError.isNotEmpty()) {
                builder.appendLine("Native load error: $nativeLoadError")
            }
            builder.appendLine("Android: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
            builder.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            builder.appendLine("ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")

            writeReportFile(STARTUP_FILE_NAME, builder.toString())
        } catch (e: Throwable) {
            // Diagnostics must never break startup.
        }
    }

    /**
     * Writes text to a file in the app's external files directory.
     * Falls back to the internal files directory when external is unavailable.
     */
    private fun writeReportFile(fileName: String, contents: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir ?: return
            if (!dir.exists()) {
                dir.mkdirs()
            }
            File(dir, fileName).writeText(contents)
        } catch (e: Throwable) {
            // Ignore: diagnostics are best-effort only.
        }
    }
}
