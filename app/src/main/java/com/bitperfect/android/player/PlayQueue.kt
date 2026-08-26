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
     */
    fun setQueue(trackPaths: List<String>, startIndex: Int = 0) = lock.withLock {
        originalOrder.clear()
        originalOrder.addAll(trackPaths)
        playOrder.clear()
        playOrder.addAll(trackPaths)
        currentIndex = if (trackPaths.isNotEmpty()) startIndex.coerceIn(0, trackPaths.size - 1) else -1
        isShuffled = false
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
        if (enabled == isShuffled) return@withLock

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
