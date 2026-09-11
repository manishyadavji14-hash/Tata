package com.bitperfect.android.library.scanner

/**
 * Decides whether what the library and the media index believe about a file still
 * matches the file itself.
 *
 * This exists because the scan's original freshness test compared the stored row's
 * size and modification time against **MediaStore's** size and modification time —
 * and the stored values had themselves been copied from MediaStore on the previous
 * scan. So the question being asked was "does the media index still say what it said
 * last time", which is always yes for an index that has gone stale. A file replaced
 * in place therefore kept its old row for good: rescanning could not fix it, because
 * the scan never opened the file to find out. A real report had the library showing
 * 48 kHz / 24-bit / 4:39 for a file the decoder read as 192 kHz / 7:05, and the
 * advice "just rescan" was worthless.
 *
 * The fix is to compare against the filesystem, which cannot be stale, and to stop
 * believing the index's technical metadata once the index is known to be wrong about
 * the file's size.
 *
 * Pure on purpose: the whole decision is arithmetic on five numbers, so it is tested
 * directly rather than through a scan that needs Android.
 */
object TrackFreshness {

    /**
     * MediaStore records modification time in whole seconds; the filesystem reports
     * milliseconds. Comparing them needs this much slack or every file looks changed.
     */
    const val MODIFIED_TOLERANCE_MS = 1_500L

    /** What the filesystem says about a file right now. */
    data class FileFacts(
        val sizeBytes: Long,
        val lastModifiedMs: Long
    )

    /**
     * Whether the media index still describes the file on disk.
     *
     * Size is the reliable signal: a different file is almost always a different
     * length, and the index's own copy of it is what goes stale. Modification time is
     * checked too, with tolerance for the second-granularity mismatch.
     *
     * @return false when the index is provably wrong, true when it agrees or when
     *   there is nothing to compare against.
     */
    fun isIndexCurrent(
        indexedSizeBytes: Long,
        indexedModifiedMs: Long,
        onDisk: FileFacts?
    ): Boolean {
        // Nothing to check against — an unreadable path, or a provider-backed entry
        // with no real file. Assume the index is right rather than re-reading
        // everything on every scan.
        if (onDisk == null) return true
        if (onDisk.sizeBytes <= 0L) return true

        if (indexedSizeBytes > 0L && indexedSizeBytes != onDisk.sizeBytes) return false

        if (indexedModifiedMs > 0L && onDisk.lastModifiedMs > 0L) {
            val drift = kotlin.math.abs(indexedModifiedMs - onDisk.lastModifiedMs)
            if (drift > MODIFIED_TOLERANCE_MS) return false
        }

        return true
    }

    /**
     * Whether the stored library row still describes the file on disk.
     *
     * Note the asymmetry with [isIndexCurrent]: a row's size and modification time
     * are written from the filesystem now, so they can be compared exactly. Rows
     * written by earlier versions hold MediaStore's second-granularity time, which is
     * why the same tolerance applies — those get re-read once and then stay accurate.
     */
    fun isRowCurrent(
        storedSizeBytes: Long,
        storedModifiedMs: Long,
        onDisk: FileFacts?
    ): Boolean {
        if (onDisk == null) return true
        if (onDisk.sizeBytes <= 0L) return true

        if (storedSizeBytes != onDisk.sizeBytes) return false

        if (storedModifiedMs > 0L && onDisk.lastModifiedMs > 0L) {
            val drift = kotlin.math.abs(storedModifiedMs - onDisk.lastModifiedMs)
            if (drift > MODIFIED_TOLERANCE_MS) return false
        }

        return true
    }

    /**
     * Keep a technical value the file actually yielded, rather than overwriting a
     * known-good one with nothing.
     *
     * The same rule the artwork path already has: a scan that fails to read a figure
     * must not erase the figure it read last time. Without it, one scan where the
     * probe could not open a file empties the format text for that track.
     *
     * Order is deliberate: what the file said now, then what the index said, then
     * what was already recorded.
     */
    fun preferMeasured(measured: Int, indexed: Int, stored: Int): Int = when {
        measured > 0 -> measured
        indexed > 0 -> indexed
        stored > 0 -> stored
        else -> 0
    }

    /** [preferMeasured] for durations, which are longs and come from milliseconds. */
    fun preferMeasuredDuration(measuredMs: Long, indexedMs: Long, storedMs: Long): Long = when {
        measuredMs > 0L -> measuredMs
        indexedMs > 0L -> indexedMs
        storedMs > 0L -> storedMs
        else -> 0L
    }
}
