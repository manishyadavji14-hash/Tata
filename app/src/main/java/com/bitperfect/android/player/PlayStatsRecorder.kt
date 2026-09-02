package com.bitperfect.android.player

/**
 * Accumulates how much of each track was actually listened to.
 *
 * "Most played" needs to mean something more useful than a play count. A track
 * started and abandoned after ten seconds is not played; a track heard twice is
 * played more than one heard once. So what is counted is **listening time**, and
 * the library turns it into a percentage of the track's duration:
 *
 * ```
 * a 4:00 track heard in full        -> 240 s -> 100%
 * ...then one minute replayed       -> 300 s -> 125%
 * a 4:00 track abandoned at 0:30    ->  30 s ->  12%
 * ```
 *
 * The total is cumulative and may exceed 100%, which is the whole point: it
 * ranks a track someone keeps returning to above one they heard once.
 *
 * **Seeking must not count.** Dragging the seek bar from 0:10 to 3:50 advances
 * the position by three and a half minutes that nobody listened to. Two things
 * prevent that being credited:
 *
 * 1. [startSegment] is called on every discontinuity — a seek, a track change, a
 *    restored position — which moves the baseline without crediting anything.
 * 2. [maxCreditPerSampleMs] caps a single sample as a backstop, for a position
 *    jump that arrived without a matching [startSegment] call.
 *
 * Because (1) makes the position monotonic within a segment, accuracy does not
 * depend on being sampled often. Sampling at the segment boundaries — pause,
 * stop, seek, end of track — is already exact, and periodic sampling only limits
 * how much is lost if the process is killed mid-track.
 *
 * Keyed on file path rather than row id so playback does not need a library
 * lookup on every sample; the caller resolves paths to rows when it flushes.
 *
 * Thread-safe: samples arrive from the sink's callbacks and from the UI's
 * position loop, which are different threads.
 */
class PlayStatsRecorder(
    /**
     * Most listening time a single sample may contribute.
     *
     * A backstop, not the primary defence — see the class note. Generous on
     * purpose: real gaps between samples happen when the device sleeps or the
     * flush loop is delayed, and under-counting real listening is worse than the
     * rare unnoticed jump this bounds.
     */
    private val maxCreditPerSampleMs: Long = DEFAULT_MAX_CREDIT_MS
) {

    private val lock = Any()

    private var currentPath: String? = null
    private var lastPositionMs: Long = 0L
    private val pending = mutableMapOf<String, Long>()

    /**
     * Begin a new continuous stretch of playback at [positionMs].
     *
     * Credits nothing. Call this whenever the position moves for a reason other
     * than audio being played: a seek, starting a track, or restoring a saved
     * position.
     */
    fun startSegment(path: String, positionMs: Long) {
        synchronized(lock) {
            currentPath = path
            lastPositionMs = positionMs.coerceAtLeast(0L)
        }
    }

    /**
     * Record that playback of [path] has reached [positionMs].
     *
     * The forward movement since the last sample is credited as listening time.
     */
    fun sample(path: String, positionMs: Long) {
        if (path.isEmpty()) return
        synchronized(lock) {
            if (path != currentPath) {
                // First sight of this track: take a baseline instead of crediting
                // everything up to the current position, which was not listened
                // to during this segment.
                currentPath = path
                lastPositionMs = positionMs.coerceAtLeast(0L)
                return
            }

            val delta = positionMs - lastPositionMs
            lastPositionMs = positionMs

            // Backwards means a seek back; zero means a stall or a repeated
            // sample. Neither is listening.
            if (delta <= 0L) return
            if (delta > maxCreditPerSampleMs) return

            pending[path] = (pending[path] ?: 0L) + delta
        }
    }

    /**
     * Take everything accumulated so far, clearing it.
     *
     * Drained rather than read so a failed write cannot be double-counted on the
     * next flush, and so the caller can skip the database entirely when the map
     * comes back empty.
     */
    fun takePending(): Map<String, Long> = synchronized(lock) {
        if (pending.isEmpty()) return emptyMap()
        val drained = pending.toMap()
        pending.clear()
        drained
    }

    /** Listening time waiting to be written, for deciding whether to flush. */
    val pendingTotalMs: Long
        get() = synchronized(lock) { pending.values.sum() }

    companion object {
        /**
         * 30 seconds. Longer than any expected gap between samples, short enough
         * that an unnoticed seek across a whole track cannot be credited.
         */
        const val DEFAULT_MAX_CREDIT_MS = 30_000L
    }
}
