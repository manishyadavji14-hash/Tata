package com.bitperfect.android.player

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Remembers what was playing, so closing and reopening the app resumes where it
 * left off.
 *
 * Deliberately SharedPreferences rather than DataStore: this is written from the
 * playback path on every position tick and read once during startup, before any
 * coroutine scope exists. `commit()` is avoided; `apply()` is asynchronous and
 * safe to call frequently.
 *
 * The queue is stored as newline-joined absolute paths. Paths, not database ids,
 * because a rescan can renumber rows but a file path stays valid, and the
 * restored queue must survive one.
 */
class PlaybackStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * A restored session. Only returned when the track still exists on disk.
     */
    data class Snapshot(
        val trackPath: String,
        val positionMs: Long,
        val queue: List<String>,
        val queueIndex: Int
    )

    /**
     * Persist the current session.
     *
     * @param positionMs where playback had reached, resumed from on next launch
     */
    fun save(trackPath: String, positionMs: Long, queue: List<String>, queueIndex: Int) {
        if (trackPath.isBlank()) return
        prefs.edit()
            .putString(KEY_TRACK, trackPath)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .putString(KEY_QUEUE, queue.joinToString("\n"))
            .putInt(KEY_QUEUE_INDEX, queueIndex)
            .apply()
    }

    /** Update just the position, which is the only field that changes often. */
    fun savePosition(positionMs: Long) {
        prefs.edit().putLong(KEY_POSITION, positionMs.coerceAtLeast(0L)).apply()
    }

    /**
     * Read back the last session.
     *
     * Returns null when nothing was stored, or when the file has since been
     * deleted or moved — restoring a queue pointing at files that no longer exist
     * would surface as a playback error on launch.
     */
    fun load(): Snapshot? {
        val path = prefs.getString(KEY_TRACK, null)?.takeIf { it.isNotBlank() } ?: return null
        if (!File(path).exists()) {
            clear()
            return null
        }

        val queue = prefs.getString(KEY_QUEUE, "")
            .orEmpty()
            .split("\n")
            .filter { it.isNotBlank() && File(it).exists() }

        // Re-derive the index by path: entries whose files vanished were dropped
        // above, so a stored index could now point at the wrong track.
        val storedIndex = prefs.getInt(KEY_QUEUE_INDEX, 0)
        val index = queue.indexOf(path).takeIf { it >= 0 } ?: storedIndex

        return Snapshot(
            trackPath = path,
            positionMs = prefs.getLong(KEY_POSITION, 0L),
            queue = queue.ifEmpty { listOf(path) },
            queueIndex = index.coerceAtLeast(0)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "playback_session"
        const val KEY_TRACK = "track_path"
        const val KEY_POSITION = "position_ms"
        const val KEY_QUEUE = "queue_paths"
        const val KEY_QUEUE_INDEX = "queue_index"
    }
}
