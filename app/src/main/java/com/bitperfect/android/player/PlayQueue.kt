package com.bitperfect.android.player

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
 * Thread safety: All methods should be called from the same thread
 * (typically the main/UI thread). Use proper synchronization if accessed
 * from multiple threads.
 */
class PlayQueue {

    private val originalOrder = mutableListOf<String>()
    private val playOrder = mutableListOf<String>()
    private var currentIndex: Int = -1
    private var isShuffled: Boolean = false
    var repeatMode: RepeatMode = RepeatMode.OFF

    /**
     * Get the current track path, or null if queue is empty.
     */
    val currentTrack: String?
        get() = if (currentIndex in playOrder.indices) playOrder[currentIndex] else null

    /**
     * Get the current position in the queue (0-indexed).
     */
    val position: Int
        get() = currentIndex

    /**
     * Get the total number of tracks in the queue.
     */
    val size: Int
        get() = playOrder.size

    /**
     * Check if the queue is empty.
     */
    val isEmpty: Boolean
        get() = playOrder.isEmpty()

    /**
     * Get all tracks in current play order.
     */
    val tracks: List<String>
        get() = playOrder.toList()

    /**
     * Add a track to the end of the queue.
     */
    fun add(trackPath: String) {
        originalOrder.add(trackPath)
        playOrder.add(trackPath)
        if (currentIndex == -1) {
            currentIndex = 0
        }
    }

    /**
     * Add multiple tracks to the end of the queue.
     */
    fun addAll(trackPaths: List<String>) {
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
    fun removeAt(index: Int): Boolean {
        if (index !in playOrder.indices) return false

        val track = playOrder[index]
        playOrder.removeAt(index)
        originalOrder.remove(track)

        when {
            playOrder.isEmpty() -> currentIndex = -1
            index < currentIndex -> currentIndex--
            index == currentIndex && currentIndex >= playOrder.size -> currentIndex = playOrder.size - 1
        }
        return true
    }

    /**
     * Move a track from one position to another.
     */
    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in playOrder.indices || toIndex !in playOrder.indices) return false
        if (fromIndex == toIndex) return true

        val track = playOrder.removeAt(fromIndex)
        playOrder.add(toIndex, track)

        // Adjust current index
        when {
            currentIndex == fromIndex -> currentIndex = toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex--
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex++
        }
        return true
    }

    /**
     * Clear the queue.
     */
    fun clear() {
        originalOrder.clear()
        playOrder.clear()
        currentIndex = -1
        isShuffled = false
    }

    /**
     * Set the queue to a new list of tracks.
     */
    fun setQueue(trackPaths: List<String>, startIndex: Int = 0) {
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
    fun next(): String? {
        if (playOrder.isEmpty()) return null

        when (repeatMode) {
            RepeatMode.ONE -> {
                // Stay on current track
                return currentTrack
            }
            RepeatMode.ALL -> {
                currentIndex = (currentIndex + 1) % playOrder.size
                return currentTrack
            }
            RepeatMode.OFF -> {
                if (currentIndex < playOrder.size - 1) {
                    currentIndex++
                    return currentTrack
                }
                return null  // End of queue
            }
        }
    }

    /**
     * Go to the previous track.
     * @return The previous track path, or null if at beginning
     */
    fun previous(): String? {
        if (playOrder.isEmpty()) return null

        when (repeatMode) {
            RepeatMode.ONE -> {
                return currentTrack
            }
            RepeatMode.ALL -> {
                currentIndex = if (currentIndex > 0) currentIndex - 1 else playOrder.size - 1
                return currentTrack
            }
            RepeatMode.OFF -> {
                if (currentIndex > 0) {
                    currentIndex--
                    return currentTrack
                }
                return currentTrack  // Stay at first track
            }
        }
    }

    /**
     * Jump to a specific index in the queue.
     */
    fun jumpTo(index: Int): String? {
        if (index !in playOrder.indices) return null
        currentIndex = index
        return currentTrack
    }

    /**
     * Enable or disable shuffle.
     * Fisher-Yates shuffle on a copy of the list, preserving original order.
     * Current track remains at the current position after shuffle.
     */
    fun setShuffle(enabled: Boolean) {
        if (enabled == isShuffled) return

        if (enabled) {
            val currentTrackPath = currentTrack
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
            val currentTrackPath = currentTrack
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
    fun isShuffleEnabled(): Boolean = isShuffled

    /**
     * Check if there is a next track available (considering repeat mode).
     */
    fun hasNext(): Boolean {
        if (playOrder.isEmpty()) return false
        return when (repeatMode) {
            RepeatMode.ONE -> true
            RepeatMode.ALL -> true
            RepeatMode.OFF -> currentIndex < playOrder.size - 1
        }
    }

    /**
     * Check if there is a previous track available.
     */
    fun hasPrevious(): Boolean {
        if (playOrder.isEmpty()) return false
        return when (repeatMode) {
            RepeatMode.ONE -> true
            RepeatMode.ALL -> true
            RepeatMode.OFF -> currentIndex > 0
        }
    }

    /**
     * Get the next track path without advancing the position.
     */
    fun peekNext(): String? {
        if (playOrder.isEmpty()) return null
        return when (repeatMode) {
            RepeatMode.ONE -> currentTrack
            RepeatMode.ALL -> playOrder[(currentIndex + 1) % playOrder.size]
            RepeatMode.OFF -> if (currentIndex < playOrder.size - 1) playOrder[currentIndex + 1] else null
        }
    }
}
