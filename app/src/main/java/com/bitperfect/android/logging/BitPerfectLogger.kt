package com.bitperfect.android.logging

import android.util.Log

/**
 * BitPerfectLogger - Structured logging system for the BitPerfect app.
 *
 * Provides categorized logging for all subsystems:
 * - BOOT: Application and service startup
 * - USB: USB device enumeration, connection, permission events
 * - CLOCK: Clock source, frequency, synchronization
 * - FORMAT: Audio format detection, negotiation, selection
 * - PCM: PCM pipeline events (configure, start, stop, data flow)
 * - DSD: DSD file detection, rate, mode selection
 * - DOP: DoP encoding events
 * - NATIVE_DSD: Native DSD transport events
 * - BUFFER: Buffer allocation, fill level, resize
 * - TRANSFER: USB isochronous transfer events
 * - ERROR: All error conditions
 * - RECOVERY: Error recovery attempts and outcomes
 *
 * Features:
 * - Per-category enable/disable
 * - Severity levels (VERBOSE, DEBUG, INFO, WARN, ERROR)
 * - Structured log entries with timestamps
 * - Debug mode toggle for verbose output
 * - Log buffer for in-app diagnostics display
 */
object BitPerfectLogger {

    /**
     * Log categories matching major subsystems.
     */
    enum class Category(val tag: String) {
        BOOT("BP:BOOT"),
        USB("BP:USB"),
        CLOCK("BP:CLOCK"),
        FORMAT("BP:FORMAT"),
        PCM("BP:PCM"),
        DSD("BP:DSD"),
        DOP("BP:DOP"),
        NATIVE_DSD("BP:NATIVE_DSD"),
        BUFFER("BP:BUFFER"),
        TRANSFER("BP:TRANSFER"),
        ERROR("BP:ERROR"),
        RECOVERY("BP:RECOVERY")
    }

    /**
     * Severity level for log entries.
     */
    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    /**
     * Structured log entry.
     */
    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val category: Category,
        val level: Level,
        val message: String,
        val details: Map<String, Any>? = null
    ) {
        override fun toString(): String {
            val detailStr = details?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: ""
            val prefix = "[${category.name}]"
            return if (detailStr.isNotEmpty()) {
                "$prefix $message | $detailStr"
            } else {
                "$prefix $message"
            }
        }
    }

    // Configuration
    private var debugMode: Boolean = false
    private val enabledCategories = mutableSetOf(*Category.entries.toTypedArray())
    private val logBuffer = ArrayDeque<LogEntry>(MAX_BUFFER_SIZE)

    private const val MAX_BUFFER_SIZE = 1000

    /**
     * Enable or disable debug mode (verbose logging).
     */
    fun setDebugMode(enabled: Boolean) {
        debugMode = enabled
    }

    /**
     * Check if debug mode is enabled.
     */
    fun isDebugMode(): Boolean = debugMode

    /**
     * Enable a specific logging category.
     */
    fun enableCategory(category: Category) {
        enabledCategories.add(category)
    }

    /**
     * Disable a specific logging category.
     */
    fun disableCategory(category: Category) {
        enabledCategories.remove(category)
    }

    /**
     * Enable all categories.
     */
    fun enableAllCategories() {
        enabledCategories.addAll(Category.entries)
    }

    /**
     * Get the recent log buffer for diagnostics display.
     */
    fun getRecentLogs(count: Int = 100): List<LogEntry> {
        return logBuffer.takeLast(count)
    }

    /**
     * Get logs filtered by category.
     */
    fun getLogsByCategory(category: Category, count: Int = 50): List<LogEntry> {
        return logBuffer.filter { it.category == category }.takeLast(count)
    }

    /**
     * Clear the log buffer.
     */
    fun clearBuffer() {
        logBuffer.clear()
    }

    // --- Logging Methods ---

    fun v(category: Category, message: String, details: Map<String, Any>? = null) {
        log(Level.VERBOSE, category, message, details)
    }

    fun d(category: Category, message: String, details: Map<String, Any>? = null) {
        log(Level.DEBUG, category, message, details)
    }

    fun i(category: Category, message: String, details: Map<String, Any>? = null) {
        log(Level.INFO, category, message, details)
    }

    fun w(category: Category, message: String, details: Map<String, Any>? = null) {
        log(Level.WARN, category, message, details)
    }

    fun e(category: Category, message: String, details: Map<String, Any>? = null) {
        log(Level.ERROR, category, message, details)
    }

    // --- Category-Specific Convenience Methods ---

    /**
     * Log USB enumeration event.
     */
    fun logUsbEnumeration(
        deviceName: String,
        vendorId: Int,
        productId: Int,
        interfaceCount: Int
    ) {
        i(Category.USB, "Device enumerated", mapOf(
            "device" to deviceName,
            "vid" to "0x${vendorId.toString(16).padStart(4, '0')}",
            "pid" to "0x${productId.toString(16).padStart(4, '0')}",
            "interfaces" to interfaceCount
        ))
    }

    /**
     * Log interface selection.
     */
    fun logInterfaceSelection(
        interfaceNumber: Int,
        altSetting: Int,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int
    ) {
        i(Category.USB, "Interface selected", mapOf(
            "interface" to interfaceNumber,
            "altSetting" to altSetting,
            "sampleRate" to sampleRate,
            "bitDepth" to bitDepth,
            "channels" to channels
        ))
    }

    /**
     * Log format negotiation.
     */
    fun logFormatNegotiation(
        requestedRate: Int,
        requestedDepth: Int,
        selectedRate: Int,
        selectedDepth: Int,
        mode: String
    ) {
        i(Category.FORMAT, "Format negotiated", mapOf(
            "requestedRate" to requestedRate,
            "requestedDepth" to requestedDepth,
            "selectedRate" to selectedRate,
            "selectedDepth" to selectedDepth,
            "mode" to mode
        ))
    }

    /**
     * Log buffer statistics.
     */
    fun logBufferStats(
        fillLevel: Float,
        underruns: Int,
        overruns: Int,
        bufferSizeMs: Int
    ) {
        d(Category.BUFFER, "Buffer stats", mapOf(
            "fillLevel" to "%.1f%%".format(fillLevel * 100),
            "underruns" to underruns,
            "overruns" to overruns,
            "bufferSizeMs" to bufferSizeMs
        ))
    }

    /**
     * Log transfer statistics.
     */
    fun logTransferStats(
        bytesTransferred: Long,
        transferRate: Long,
        errors: Int
    ) {
        d(Category.TRANSFER, "Transfer stats", mapOf(
            "bytesTransferred" to bytesTransferred,
            "transferRate" to "$transferRate B/s",
            "errors" to errors
        ))
    }

    /**
     * Log DSD mode selection.
     */
    fun logDsdModeSelection(
        dsdRate: Int,
        selectedMode: String,
        transportRate: Int
    ) {
        i(Category.DSD, "DSD mode selected", mapOf(
            "dsdRate" to dsdRate,
            "mode" to selectedMode,
            "transportRate" to transportRate
        ))
    }

    /**
     * Log error with recovery context.
     */
    fun logError(
        errorType: String,
        message: String,
        recoverable: Boolean,
        details: Map<String, Any>? = null
    ) {
        val allDetails = mutableMapOf<String, Any>(
            "errorType" to errorType,
            "recoverable" to recoverable
        )
        details?.let { allDetails.putAll(it) }
        e(Category.ERROR, message, allDetails)
    }

    /**
     * Log recovery attempt.
     */
    fun logRecovery(
        errorType: String,
        action: String,
        success: Boolean,
        details: Map<String, Any>? = null
    ) {
        val allDetails = mutableMapOf<String, Any>(
            "errorType" to errorType,
            "action" to action,
            "success" to success
        )
        details?.let { allDetails.putAll(it) }
        val level = if (success) Level.INFO else Level.WARN
        log(level, Category.RECOVERY, "Recovery: $action", allDetails)
    }

    // --- Internal ---

    private fun log(level: Level, category: Category, message: String, details: Map<String, Any>?) {
        // Skip if category is disabled
        if (category !in enabledCategories) return

        // Skip verbose/debug in non-debug mode
        if (!debugMode && (level == Level.VERBOSE || level == Level.DEBUG)) return

        // Create log entry
        val entry = LogEntry(
            category = category,
            level = level,
            message = message,
            details = details
        )

        // Add to buffer
        synchronized(logBuffer) {
            if (logBuffer.size >= MAX_BUFFER_SIZE) {
                logBuffer.removeFirst()
            }
            logBuffer.addLast(entry)
        }

        // Output to Android logcat
        val logMessage = entry.toString()
        when (level) {
            Level.VERBOSE -> Log.v(category.tag, logMessage)
            Level.DEBUG -> Log.d(category.tag, logMessage)
            Level.INFO -> Log.i(category.tag, logMessage)
            Level.WARN -> Log.w(category.tag, logMessage)
            Level.ERROR -> Log.e(category.tag, logMessage)
        }
    }
}
