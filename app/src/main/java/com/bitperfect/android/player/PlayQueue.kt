package com.bitperfect.android.player

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Repeat modes for queue playback.
 */
enum class RepeatMode {
    OFF,    // Stop after last track
    ONE,    // Repeat current track
    ALL     // Repeat entire queue
}

/**
 * PlayQueue - manages an ordered list of tracks for playback.
 *
 * Features:
 * - Add, remove, reorder, and clear tracks
 * - Shuffle using Fisher-Yates algorithm on a copy (preserves original order)
 * - Repeat modes: Off, One, All
 * - Current index tracking with next/previous logic
 *
 * Thread safety: All public methods are synchronized using a ReentrantLock
 * to allow safe concurrent access from multiple threads (UI thread,
 * playback service callbacks, native gapless engine callbacks).
 */
class PlayQueue {

    private val lock = ReentrantLock()
    private val originalOrder = mutableListOf<String>()
    private val playOrder = mutableListOf<String>()
    private var currentIndex: Int = -1
    private var isShuffled: Boolean = false
    var repeatMode: RepeatMode
        get() = lock.withLock { _repeatMode }
        set(value) { lock.withLock { _repeatMode = value } }
    private var _repeatMode: RepeatMode = RepeatMode.OFF

    /**
     * Get the current track path, or null if queue is empty.
     */
    val currentTrack: String?
        get() = lock.withLock {
            if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
        }

    /**
     * Get the current position in the queue (0-indexed).
     */
    val position: Int
        get() = lock.withLock { currentIndex }

    /**
     * Get the total number of tracks in the queue.
     */
    val size: Int
        get() = lock.withLock { playOrder.size }

    /**
     * Check if the queue is empty.
     */
    val isEmpty: Boolean
        get() = lock.withLock { playOrder.isEmpty() }

    /**
     * Get all tracks in current play order.
     */
    val tracks: List<String>
        get() = lock.withLock { playOrder.toList() }

    /**
     * Add a track to the end of the queue.
     */
    fun add(trackPath: String) = lock.withLock {
        originalOrder.add(trackPath)
        playOrder.add(trackPath)
        if (currentIndex == -1) {
            currentIndex = 0
        }
    }

    /**
     * Add multiple tracks to the end of the queue.
     */
    fun addAll(trackPaths: List<String>) = lock.withLock {
        originalOrder.addAll(trackPaths)
        playOrder.addAll(trackPaths)
        if (currentIndex == -1 && playOrder.isNotEmpty()) {
            currentIndex = 0
        }
    }

    /**
     * Insert a track at a specific position.
     *
     * Used by "play next", which places a track immediately after the current
     * one. The insert shifts the current index when it lands before it, so the
     * track being played is unaffected.
     */
    fun insertAt(index: Int, trackPath: String) = lock.withLock {
        insertAtLocked(index, trackPath)
    }

    /** Insert body shared by [insertAt] and [insertAfterCurrent]. Caller holds the lock. */
    private fun insertAtLocked(index: Int, trackPath: String) {
        val target = index.coerceIn(0, playOrder.size)
        playOrder.add(target, trackPath)

        // Keep the original order meaningful for un-shuffling.
        if (isShuffled) originalOrder.add(trackPath) else originalOrder.add(target, trackPath)

        if (currentIndex == -1) {
            currentIndex = 0
        } else if (target <= currentIndex) {
            currentIndex++
        }
    }

    /**
     * Remove a track at the specified index.
     * Adjusts currentIndex as needed.
     */
    fun removeAt(index: Int): Boolean = lock.withLock {
        if (index !in playOrder.indices) return@withLock false

        val track = playOrder[index]
        playOrder.removeAt(index)
        originalOrder.remove(track)

        when {
            playOrder.isEmpty() -> currentIndex = -1
            index < currentIndex -> currentIndex--
            index == currentIndex && currentIndex >= playOrder.size -> currentIndex = playOrder.size - 1
        }
        true
    }

    /**
     * Outcome of [removeAtTrackingCurrent].
     *
     * @property removed false when the index was out of range and nothing changed.
     * @property wasCurrent true when the removed entry was the one playing, so
     *   the caller must start [replacement] or stop.
     * @property replacement the track now at the current position, or null when
     *   the queue is empty.
     */
    data class RemoveOutcome(
        val removed: Boolean,
        val wasCurrent: Boolean,
        val replacement: String?
    )

    /**
     * Removes an entry and reports, under one lock acquisition, whether it was
     * the entry being played and what replaced it.
     *
     * Reading `position`, calling `removeAt` and then reading `currentTrack`
     * takes the lock three times. The audio worker can finish a track and
     * advance the queue in between, so the caller would decide based on a
     * position that has already moved - leaving a removed track playing, or
     * starting the wrong replacement.
     */
    fun removeAtTrackingCurrent(index: Int): RemoveOutcome = lock.withLock {
        if (index !in playOrder.indices) {
            return@withLock RemoveOutcome(removed = false, wasCurrent = false, replacement = null)
        }

        val wasCurrent = index == currentIndex
        val track = playOrder[index]
        playOrder.removeAt(index)
        originalOrder.remove(track)

        when {
            playOrder.isEmpty() -> currentIndex = -1
            index < currentIndex -> currentIndex--
            index == currentIndex && currentIndex >= playOrder.size ->
                currentIndex = playOrder.size - 1
        }

        RemoveOutcome(
            removed = true,
            wasCurrent = wasCurrent,
            replacement = if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
        )
    }

    /**
     * Inserts a track directly after the one playing, atomically.
     *
     * Returns false when the queue is empty, so the caller can decide to start
     * the track instead of queuing it. Reading `isEmpty` and `position`
     * separately before inserting would let the audio worker advance in
     * between and place the track after the wrong entry.
     */
    fun insertAfterCurrent(trackPath: String): Boolean = lock.withLock {
        if (playOrder.isEmpty()) return@withLock false
        insertAtLocked(currentIndex + 1, trackPath)
        true
    }

    /**
     * Move a track from one position to another.
     */
    fun move(fromIndex: Int, toIndex: Int): Boolean = lock.withLock {
        if (fromIndex !in playOrder.indices || toIndex !in playOrder.indices) return@withLock false
        if (fromIndex == toIndex) return@withLock true

        val track = playOrder.removeAt(fromIndex)
        playOrder.add(toIndex, track)

        // Adjust current index
        when {
            currentIndex == fromIndex -> currentIndex = toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex--
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex++
        }
        true
    }

    /**
     * Clear the queue.
     */
    fun clear() = lock.withLock {
        originalOrder.clear()
        playOrder.clear()
        currentIndex = -1
        isShuffled = false
    }

    /**
     * Set the queue to a new list of tracks.
     *
     * **Shuffle survives.** This used to reset it, which silently defeated the
     * feature: every way of starting playback replaces the queue, so turning
     * shuffle on and then tapping a song in the library left the queue in list
     * order while the button still showed shuffle as active. Tracks then played
     * in order at every track end, which is what "shuffle doesn't apply" looks
     * like.
     *
     * The track at [startIndex] stays the one that plays; the rest is reshuffled
     * behind it, so an explicit choice is still honoured.
     */
    fun setQueue(trackPaths: List<String>, startIndex: Int = 0) = lock.withLock {
        val wasShuffled = isShuffled

        originalOrder.clear()
        originalOrder.addAll(trackPaths)
        playOrder.clear()
        playOrder.addAll(trackPaths)
        currentIndex = if (trackPaths.isNotEmpty()) startIndex.coerceIn(0, trackPaths.size - 1) else -1
        isShuffled = false

        if (wasShuffled) setShuffleLocked(true)
    }

    /**
     * Advance to the next track.
     * @return The next track path, or null if at end (respects repeat mode)
     */
    fun next(): String? = lock.withLock {
        if (playOrder.isEmpty()) return@withLock null

        when (_repeatMode) {
            RepeatMode.ONE -> {
                // Stay on current track
                return@withLock if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
            }
            RepeatMode.ALL -> {
                currentIndex = (currentIndex + 1) % playOrder.size
                return@withLock if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
            }
            RepeatMode.OFF -> {
                if (currentIndex < playOrder.size - 1) {
                    currentIndex++
                    return@withLock if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
                }
                return@withLock null  // End of queue
            }
        }
    }

    /**
     * Go to the previous track.
     * @return The previous track path, or null if at beginning
     */
    fun previous(): String? = lock.withLock {
        if (playOrder.isEmpty()) return@withLock null

        when (_repeatMode) {
            RepeatMode.ONE -> {
                return@withLock if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
            }
            RepeatMode.ALL -> {
                currentIndex = if (currentIndex > 0) currentIndex - 1 else playOrder.size - 1
                return@withLock if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
            }
            RepeatMode.OFF -> {
                if (currentIndex > 0) {
                    currentIndex--
                }
                return@withLock if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
            }
        }
    }

    /**
     * Jump to a specific index in the queue.
     */
    fun jumpTo(index: Int): String? = lock.withLock {
        if (index !in playOrder.indices) return@withLock null
        currentIndex = index
        playOrder[currentIndex]
    }

    /**
     * Enable or disable shuffle.
     * Fisher-Yates shuffle on a copy of the list, preserving original order.
     * Current track remains at the current position after shuffle.
     */
    fun setShuffle(enabled: Boolean) = lock.withLock {
        setShuffleLocked(enabled)
    }

    /** Shuffle body shared with [setQueue]. Caller holds the lock. */
    private fun setShuffleLocked(enabled: Boolean) {
        if (enabled == isShuffled) return

        if (enabled) {
            val currentTrackPath = if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
            val remaining = playOrder.toMutableList()

            // Remove current track so we can put it first
            if (currentTrackPath != null) {
                remaining.remove(currentTrackPath)
            }

            // Fisher-Yates shuffle
            for (i in remaining.size - 1 downTo 1) {
                val j = (0..i).random()
                remaining[i] = remaining[j].also { remaining[j] = remaining[i] }
            }

            playOrder.clear()
            if (currentTrackPath != null) {
                playOrder.add(currentTrackPath)
                playOrder.addAll(remaining)
                currentIndex = 0
            } else {
                playOrder.addAll(remaining)
            }
        } else {
            // Restore original order
            val currentTrackPath = if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
            playOrder.clear()
            playOrder.addAll(originalOrder)
            currentIndex = if (currentTrackPath != null) {
                playOrder.indexOf(currentTrackPath).coerceAtLeast(0)
            } else {
                0
            }
        }

        isShuffled = enabled
    }

    /**
     * Check if shuffle is enabled.
     */
    fun isShuffleEnabled(): Boolean = lock.withLock { isShuffled }

    /**
     * Check if there is a next track available (considering repeat mode).
     */
    fun hasNext(): Boolean = lock.withLock {
        if (playOrder.isEmpty()) return@withLock false
        when (_repeatMode) {
            RepeatMode.ONE -> true
            RepeatMode.ALL -> true
            RepeatMode.OFF -> currentIndex < playOrder.size - 1
        }
    }

    /**
     * Check if there is a previous track available.
     */
    fun hasPrevious(): Boolean = lock.withLock {
        if (playOrder.isEmpty()) return@withLock false
        when (_repeatMode) {
            RepeatMode.ONE -> true
            RepeatMode.ALL -> true
            RepeatMode.OFF -> currentIndex > 0
        }
    }

    /**
     * Get the next track path without advancing the position.
     */
    fun peekNext(): String? = lock.withLock {
        if (playOrder.isEmpty()) return@withLock null
        when (_repeatMode) {
            RepeatMode.ONE -> if (currentIndex in playOrder.indices) playOrder[currentIndex] else null
            RepeatMode.ALL -> playOrder[(currentIndex + 1) % playOrder.size]
            RepeatMode.OFF -> if (currentIndex < playOrder.size - 1) playOrder[currentIndex + 1] else null
        }
    }
}
