package com.bitperfect.android.player

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Unit tests for PlayQueue.
 *
 * Tests: add/remove, reorder, shuffle (preserves all items),
 * repeat modes (Off wraps to null, One stays, All wraps), next/previous at boundaries.
 */
@DisplayName("PlayQueue Tests")
class PlayQueueTest {

    private lateinit var queue: PlayQueue

    @BeforeEach
    fun setUp() {
        queue = PlayQueue()
    }

    // === Add/Remove ===

    @Test
    @DisplayName("Add single track")
    fun addSingleTrack() {
        queue.add("/music/track1.flac")
        assertEquals(1, queue.size)
        assertEquals("/music/track1.flac", queue.currentTrack)
        assertEquals(0, queue.position)
    }

    @Test
    @DisplayName("Add multiple tracks")
    fun addMultipleTracks() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac", "/music/c.flac"))
        assertEquals(3, queue.size)
        assertEquals("/music/a.flac", queue.currentTrack)
    }

    @Test
    @DisplayName("Remove track adjusts index")
    fun removeTrackAdjustsIndex() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac", "/music/c.flac"))
        queue.next() // now at index 1 (b.flac)

        queue.removeAt(0) // Remove a.flac (before current)
        assertEquals("/music/b.flac", queue.currentTrack)
        assertEquals(0, queue.position) // Index should adjust down
    }

    @Test
    @DisplayName("Remove current track at end adjusts to last")
    fun removeCurrentAtEnd() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.next() // at index 1

        queue.removeAt(1) // Remove current (b.flac)
        assertEquals(0, queue.position)
        assertEquals("/music/a.flac", queue.currentTrack)
    }

    @Test
    @DisplayName("Remove all tracks empties queue")
    fun removeAllTracks() {
        queue.add("/music/a.flac")
        queue.removeAt(0)
        assertTrue(queue.isEmpty)
        assertNull(queue.currentTrack)
    }

    @Test
    @DisplayName("Remove at invalid index returns false")
    fun removeInvalidIndex() {
        queue.add("/music/a.flac")
        assertFalse(queue.removeAt(5))
        assertFalse(queue.removeAt(-1))
    }

    // === Reorder ===

    @Test
    @DisplayName("Move track forward")
    fun moveForward() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac", "/music/c.flac"))
        // Current is at 0 (a.flac)
        queue.move(0, 2) // Move a to position 2
        assertEquals(2, queue.position) // Current track follows
        assertEquals("/music/a.flac", queue.currentTrack)
        assertEquals(listOf("/music/b.flac", "/music/c.flac", "/music/a.flac"), queue.tracks)
    }

    @Test
    @DisplayName("Move track backward")
    fun moveBackward() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac", "/music/c.flac"))
        queue.next() // at b.flac (index 1)

        queue.move(2, 0) // Move c.flac to front
        assertEquals(2, queue.position) // b.flac shifted to index 2
        assertEquals("/music/b.flac", queue.currentTrack)
    }

    @Test
    @DisplayName("Move same position is no-op")
    fun moveSamePosition() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        assertTrue(queue.move(0, 0))
        assertEquals("/music/a.flac", queue.currentTrack)
    }

    @Test
    @DisplayName("Move invalid indices return false")
    fun moveInvalidIndices() {
        queue.add("/music/a.flac")
        assertFalse(queue.move(0, 5))
        assertFalse(queue.move(-1, 0))
    }

    // === Shuffle ===

    @Test
    @DisplayName("Shuffle preserves all items")
    fun shufflePreservesAllItems() {
        val tracks = (1..20).map { "/music/track$it.flac" }
        queue.addAll(tracks)

        queue.setShuffle(true)

        // All original tracks should still be present
        val shuffled = queue.tracks.toSet()
        val original = tracks.toSet()
        assertEquals(original, shuffled)
        assertEquals(tracks.size, queue.size)
    }

    @Test
    @DisplayName("Shuffle keeps current track at front")
    fun shuffleKeepsCurrentAtFront() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac", "/music/c.flac"))
        queue.next() // Current is b.flac

        queue.setShuffle(true)
        assertEquals("/music/b.flac", queue.currentTrack)
        assertEquals(0, queue.position)
    }

    @Test
    @DisplayName("Unshuffle restores original order")
    fun unshuffleRestoresOrder() {
        val tracks = listOf("/music/a.flac", "/music/b.flac", "/music/c.flac")
        queue.addAll(tracks)

        queue.setShuffle(true)
        queue.setShuffle(false)

        assertEquals(tracks, queue.tracks)
    }

    @Test
    @DisplayName("Double shuffle is no-op")
    fun doubleShuffleNoOp() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.setShuffle(true)
        val firstShuffle = queue.tracks.toList()
        queue.setShuffle(true)  // Already shuffled
        assertEquals(firstShuffle, queue.tracks)
    }

    @Test
    @DisplayName("IsShuffleEnabled reflects state")
    fun isShuffleEnabled() {
        assertFalse(queue.isShuffleEnabled())
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.setShuffle(true)
        assertTrue(queue.isShuffleEnabled())
        queue.setShuffle(false)
        assertFalse(queue.isShuffleEnabled())
    }

    // === Repeat Mode OFF ===

    @Test
    @DisplayName("RepeatMode.OFF: next returns null at end")
    fun repeatOffNextAtEnd() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.repeatMode = RepeatMode.OFF

        queue.next() // b.flac
        val result = queue.next()
        assertNull(result) // End of queue
    }

    @Test
    @DisplayName("RepeatMode.OFF: previous stays at first")
    fun repeatOffPreviousAtStart() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.repeatMode = RepeatMode.OFF

        val result = queue.previous()
        assertNotNull(result)
        assertEquals("/music/a.flac", result) // Stays at first
    }

    // === Repeat Mode ONE ===

    @Test
    @DisplayName("RepeatMode.ONE: next stays on same track")
    fun repeatOneNextStays() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.repeatMode = RepeatMode.ONE

        val result = queue.next()
        assertEquals("/music/a.flac", result) // Stays on same track
    }

    @Test
    @DisplayName("RepeatMode.ONE: previous stays on same track")
    fun repeatOnePreviousStays() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.repeatMode = RepeatMode.ONE

        val result = queue.previous()
        assertEquals("/music/a.flac", result)
    }

    // === Repeat Mode ALL ===

    @Test
    @DisplayName("RepeatMode.ALL: next wraps to first")
    fun repeatAllNextWraps() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.repeatMode = RepeatMode.ALL

        queue.next() // b.flac
        val result = queue.next()
        assertEquals("/music/a.flac", result) // Wraps around
    }

    @Test
    @DisplayName("RepeatMode.ALL: previous wraps to last")
    fun repeatAllPreviousWraps() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac", "/music/c.flac"))
        queue.repeatMode = RepeatMode.ALL

        val result = queue.previous()
        assertEquals("/music/c.flac", result) // Wraps to last
    }

    // === Boundary Conditions ===

    @Test
    @DisplayName("Next on empty queue returns null")
    fun nextOnEmptyQueue() {
        assertNull(queue.next())
    }

    @Test
    @DisplayName("Previous on empty queue returns null")
    fun previousOnEmptyQueue() {
        assertNull(queue.previous())
    }

    @Test
    @DisplayName("Queue starts with index -1 when empty")
    fun emptyQueueIndex() {
        assertEquals(-1, queue.position)
        assertNull(queue.currentTrack)
    }

    @Test
    @DisplayName("setQueue replaces all content")
    fun setQueueReplaces() {
        queue.addAll(listOf("/old/a.flac", "/old/b.flac"))
        queue.setQueue(listOf("/new/x.flac", "/new/y.flac"), startIndex = 1)

        assertEquals(2, queue.size)
        assertEquals("/new/y.flac", queue.currentTrack)
    }

    @Test
    @DisplayName("jumpTo valid index")
    fun jumpToValid() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac", "/music/c.flac"))
        val result = queue.jumpTo(2)
        assertEquals("/music/c.flac", result)
        assertEquals(2, queue.position)
    }

    @Test
    @DisplayName("jumpTo invalid index returns null")
    fun jumpToInvalid() {
        queue.add("/music/a.flac")
        assertNull(queue.jumpTo(5))
    }

    @Test
    @DisplayName("clear empties everything")
    fun clearQueue() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.clear()
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.size)
        assertNull(queue.currentTrack)
    }

    // === Has Next/Previous ===

    @Test
    @DisplayName("hasNext correct for RepeatMode.OFF")
    fun hasNextOff() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.repeatMode = RepeatMode.OFF

        assertTrue(queue.hasNext()) // At first, has next
        queue.next()
        assertFalse(queue.hasNext()) // At last, no next
    }

    @Test
    @DisplayName("hasNext always true for RepeatMode.ALL")
    fun hasNextAll() {
        queue.add("/music/a.flac")
        queue.repeatMode = RepeatMode.ALL
        assertTrue(queue.hasNext())
    }

    @Test
    @DisplayName("hasPrevious correct for RepeatMode.OFF")
    fun hasPreviousOff() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        queue.repeatMode = RepeatMode.OFF

        assertFalse(queue.hasPrevious()) // At first
        queue.next()
        assertTrue(queue.hasPrevious()) // At second
    }

    // === Peek Next ===

    @Test
    @DisplayName("peekNext does not advance position")
    fun peekNextNoAdvance() {
        queue.addAll(listOf("/music/a.flac", "/music/b.flac"))
        val next = queue.peekNext()
        assertEquals("/music/b.flac", next)
        assertEquals(0, queue.position) // Position unchanged
    }

    @Test
    @DisplayName("peekNext returns null at end with RepeatMode.OFF")
    fun peekNextNullAtEnd() {
        queue.add("/music/a.flac")
        queue.repeatMode = RepeatMode.OFF
        assertNull(queue.peekNext())
    }
}
