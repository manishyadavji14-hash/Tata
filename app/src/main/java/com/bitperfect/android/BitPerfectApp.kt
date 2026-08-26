package com.bitperfect.android

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.bitperfect.android.library.MusicLibrary
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * BitPerfect Application class.
 *
 * IMPORTANT ORDER OF OPERATIONS:
 *  1. Install the uncaught exception handler (must be first).
 *  2. Load the native library (guarded, never fatal).
 *
 * The native library is deliberately NOT loaded from a `companion object`
 * initializer. Static initializers run during class loading, i.e. before
 * onCreate(), so a failure there produces an ExceptionInInitializerError that
 * kills the process before any handler is installed and before any diagnostic
 * can be written. Loading it explicitly inside onCreate() keeps startup
 * recoverable.
 *
 * Crash reports are written to every location that might be readable:
 *   /storage/emulated/0/Download/bitperfect_crash.txt   (via MediaStore)
 *   /storage/emulated/0/Android/data/com.bitperfect.android/files/crash.txt
 * and are additionally shown on screen by CrashActivity.
 */
class BitPerfectApp : Application() {

    companion object {
        private const val TAG = "BitPerfectApp"

        private const val CRASH_FILE_NAME = "crash.txt"
        private const val STARTUP_FILE_NAME = "startup.txt"
        private const val DOWNLOAD_CRASH_NAME = "bitperfect_crash.txt"
        private const val DOWNLOAD_STARTUP_NAME = "bitperfect_startup.txt"

        /** True when libbitperfect_engine.so loaded successfully. */
        @Volatile
        var isNativeLoaded: Boolean = false
            private set

        /** Empty on success, otherwise the failure reason. */
        @Volatile
        var nativeLoadError: String = ""
            private set

        /** Last captured crash report, used by CrashActivity. */
        @Volatile
        var lastCrashReport: String = ""
            internal set
    }

    override fun onCreate() {
        // 1. Diagnostics first, so everything after this point is observable.
        installCrashHandler()

        super.onCreate()

        // 2. Native library second, guarded so a failure is never fatal.
        loadNativeLibrary()

        // 3. Initialize application-scoped components in ServiceLocator.
        initializeAppComponents()

        writeStartupLog()
        Log.i(TAG, "BitPerfect application started (native loaded: $isNativeLoaded)")
    }

    /**
     * Loads the native engine. Catches Throwable rather than only
     * UnsatisfiedLinkError: a mis-linked or mis-aligned .so can surface as
     * other Error subclasses, and none of them should prevent the UI from
     * starting.
     */
    private fun loadNativeLibrary() {
        try {
            System.loadLibrary("bitperfect_engine")
            isNativeLoaded = true
            nativeLoadError = ""
            Log.i(TAG, "Native library loaded successfully")
        } catch (t: Throwable) {
            isNativeLoaded = false
            nativeLoadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "Failed to load native library: $nativeLoadError")
        }
    }

    /**
     * Initializes application-scoped components and registers them
     * in the ServiceLocator. MusicLibrary is app-scoped because it
     * survives service restarts and is needed by the library UI.
     */
    private fun initializeAppComponents() {
        val musicLibrary = MusicLibrary()
        ServiceLocator.musicLibrary = musicLibrary
        Log.i(TAG, "ServiceLocator.musicLibrary initialized")
    }

    /**
     * Installs a handler that persists the stack trace, then shows it on
     * screen, then delegates to the previous handler so the process still
     * terminates normally.
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildCrashReport(thread, throwable)
                lastCrashReport = report

                writeToExternalFilesDir(CRASH_FILE_NAME, report)
                writeToDownloads(DOWNLOAD_CRASH_NAME, report)
                showCrashActivity(report)
            } catch (t: Throwable) {
                // A crash handler must never crash.
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        val out = PrintWriter(writer)

        out.println("=== BitPerfect Crash Report ===")
        out.println("Time: ${Date()}")
        out.println("Thread: ${thread.name}")
        out.println("Android: API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        out.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        out.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
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
            if (next === cause) break
            cause = next
            depth++
        }

        out.flush()
        return writer.toString()
    }

    private fun writeStartupLog() {
        val report = buildString {
            appendLine("=== BitPerfect Startup Log ===")
            appendLine("Time: ${Date()}")
            appendLine("Native loaded: $isNativeLoaded")
            if (nativeLoadError.isNotEmpty()) {
                appendLine("Native load error: $nativeLoadError")
            }
            appendLine("Android: API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        }

        writeToExternalFilesDir(STARTUP_FILE_NAME, report)
        writeToDownloads(DOWNLOAD_STARTUP_NAME, report)
    }

    /** Launches CrashActivity so the trace is visible without file access. */
    private fun showCrashActivity(report: String) {
        try {
            val intent = Intent(this, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(CrashActivity.EXTRA_REPORT, report)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            // Best effort only.
        }
    }

    /** App-private external dir. Readable by file managers, sometimes not by Termux. */
    private fun writeToExternalFilesDir(fileName: String, contents: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir ?: return
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText(contents)
        } catch (t: Throwable) {
            // Best effort only.
        }
    }

    /**
     * Writes to the shared Downloads collection via MediaStore. This needs no
     * runtime permission on API 29+ and lands in a directory Termux can read.
     */
    private fun writeToDownloads(fileName: String, contents: String) {
        try {
            val resolver = contentResolver

            // Replace any previous report with the same name.
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName)
            )

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return

            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(contents.toByteArray())
                stream.flush()
            }
        } catch (t: Throwable) {
            // Best effort only.
        }
    }
}
