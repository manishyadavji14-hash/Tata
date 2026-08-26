package com.bitperfect.android.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SleepTimer - pauses playback after a configurable duration.
 *
 * Scheduled on the main looper rather than a sleeping thread, so cancelling and
 * extending take effect immediately and no thread is parked for hours. This
 * means [onExpired] is invoked on the main thread.
 *
 * Timings use [SystemClock.elapsedRealtime] because it is monotonic; wall clock
 * time can jump backwards and leave a timer that never fires.
 *
 * State is guarded by a lock rather than volatile fields. Volatile gives
 * visibility but not atomicity, and this class needs atomicity: the control
 * methods are called from whichever thread drives playback while expiry runs on
 * the main thread, so a bare check-then-act would let a cancelled timer still
 * pause playback.
 */
class SleepTimer(
    durationMs: Long,
    private val onExpired: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val lock = ReentrantLock()

    /** Deadline on the monotonic clock. Guarded by [lock]. */
    private var deadlineElapsedMs: Long = 0L

    /** Guarded by [lock]. */
    private var isRunning: Boolean = false

    /** Guarded by [lock]. */
    private var totalDurationMs: Long = durationMs.coerceAtLeast(0L)

    /**
     * Identifies the current countdown.
     *
     * `Handler.removeCallbacks` cannot stop a runnable that has already been
     * dispatched and is waiting to run, so removal alone is not enough to
     * guarantee a cancelled or extended timer will not fire. Each scheduled
     * expiry carries the generation it belongs to and does nothing if that no
     * longer matches, which closes the window completely.
     *
     * Guarded by [lock].
     */
    private var generation: Int = 0

    /** The runnable currently posted, kept so it can be removed. Guarded by [lock]. */
    private var pendingExpiry: Runnable? = null

    /** Total duration, which [extend] increases. */
    val durationMs: Long
        get() = lock.withLock { totalDurationMs }

    /**
     * Remaining time in milliseconds, or null when not running.
     */
    val remainingMs: Long?
        get() = lock.withLock {
            if (!isRunning) return@withLock null
            (deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

    /**
     * Whether the timer is currently counting down.
     */
    val active: Boolean
        get() = lock.withLock { isRunning }

    /**
     * Start, or restart, the countdown.
     */
    fun start() {
        lock.withLock {
            clearPendingLocked()
            if (totalDurationMs <= 0L) {
                isRunning = false
                deadlineElapsedMs = 0L
                return
            }
            deadlineElapsedMs = SystemClock.elapsedRealtime() + totalDurationMs
            isRunning = true
            scheduleLocked(totalDurationMs)
        }
    }

    /**
     * Cancel the countdown. Safe to call when not running.
     */
    fun cancel() {
        lock.withLock {
            isRunning = false
            deadlineElapsedMs = 0L
            clearPendingLocked()
        }
    }

    /**
     * Add time to a running countdown.
     *
     * This mutates the timer in place. Creating a replacement would leave the
     * caller holding a handle to a cancelled timer, so `remainingMs` would read
     * null while a different instance was still counting down.
     */
    fun extend(additionalMs: Long) {
        lock.withLock {
            if (!isRunning || additionalMs <= 0L) return

            val remaining =
                (deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val newRemaining = remaining + additionalMs

            clearPendingLocked()
            totalDurationMs += additionalMs
            deadlineElapsedMs = SystemClock.elapsedRealtime() + newRemaining
            scheduleLocked(newRemaining)
        }
    }

    /** Caller holds [lock]. */
    private fun scheduleLocked(delayMs: Long) {
        val scheduledGeneration = ++generation
        val runnable = Runnable { onExpiry(scheduledGeneration) }
        pendingExpiry = runnable
        handler.postDelayed(runnable, delayMs)
    }

    /**
     * Invalidates any scheduled expiry. Bumping the generation is what actually
     * makes this safe; removing the callback only avoids a pointless wake-up.
     *
     * Caller holds [lock].
     */
    private fun clearPendingLocked() {
        generation++
        pendingExpiry?.let { handler.removeCallbacks(it) }
        pendingExpiry = null
    }

    private fun onExpiry(scheduledGeneration: Int) {
        val shouldFire = lock.withLock {
            if (!isRunning || scheduledGeneration != generation) return@withLock false
            isRunning = false
            pendingExpiry = null
            true
        }

        // Invoked without the lock held: the callback pauses playback, which
        // takes locks of its own, and holding both would risk a deadlock.
        if (shouldFire) onExpired()
    }
}
