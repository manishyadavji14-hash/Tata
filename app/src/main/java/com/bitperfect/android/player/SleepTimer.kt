package com.bitperfect.android.player

/**
 * SleepTimer - pauses playback after a configurable duration.
 *
 * Features:
 * - Configurable duration in milliseconds
 * - Countdown with remaining time query
 * - Callback fired when timer expires
 * - Can be cancelled at any time
 *
 * Note: In an Android environment, this would use Handler/Looper or
 * coroutines for timing. This implementation provides the logic
 * structure with a simple thread-based approach.
 */
class SleepTimer(
    private val durationMs: Long,
    private val onExpired: () -> Unit
) {
    private var startTimeMs: Long = 0L
    private var isActive: Boolean = false
    private var timerThread: Thread? = null

    /**
     * Get remaining time in milliseconds.
     * Returns null if timer is not active.
     */
    val remainingMs: Long?
        get() {
            if (!isActive) return null
            val elapsed = System.currentTimeMillis() - startTimeMs
            return (durationMs - elapsed).coerceAtLeast(0L)
        }

    /**
     * Check if the timer is currently active.
     */
    val active: Boolean
        get() = isActive

    /**
     * Start the timer.
     */
    fun start() {
        cancel()
        startTimeMs = System.currentTimeMillis()
        isActive = true

        timerThread = Thread {
            try {
                Thread.sleep(durationMs)
                if (isActive) {
                    isActive = false
                    onExpired()
                }
            } catch (e: InterruptedException) {
                // Timer was cancelled
            }
        }.apply {
            isDaemon = true
            name = "SleepTimer"
            start()
        }
    }

    /**
     * Cancel the timer.
     */
    fun cancel() {
        isActive = false
        timerThread?.interrupt()
        timerThread = null
    }

    /**
     * Extend the timer by additional milliseconds.
     */
    fun extend(additionalMs: Long) {
        if (!isActive) return
        // Cancel and restart with remaining + additional time
        val remaining = remainingMs ?: return
        cancel()
        val newTimer = SleepTimer(remaining + additionalMs, onExpired)
        newTimer.start()
    }
}
