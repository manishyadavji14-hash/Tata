package com.bitperfect.android.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Shuffle and repeat surviving the things that actually happen during playback.
 *
 * Reported symptom: "when a song ends it doesn't change like shuffle or repeat set
 * automatically". The cause was not the end-of-track path — that has always gone
 * through [PlayQueue.next] — but [PlayQueue.setQueue], which reset shuffle. Since
 * every way of starting playback replaces the queue, turning shuffle on and then
 * tapping a song in the library silently returned the queue to list order while
 * the button still showed shuffle as active.
 *
 * These tests cover the interaction rather than the individual operations, which
 * `PlayQueueTest` already does.
 */
@DisplayName("Shuffle and repeat across queue changes")
class ShuffleQueueTest {

    private lateinit var queue: PlayQueue

    private val library = (1..12).map { "/music/track$it.flac" }

    @BeforeEach
    fun setUp() {
        queue = PlayQueue()
    }

    /** Walk the queue by [PlayQueue.next], as the end of a track does. */
    private fun playThrough(steps: Int): List<String> = buildList {
        repeat(steps) { queue.next()?.let { add(it) } ?: return@buildList }
    }

    // --- The reported bug ---

    @Test
    @DisplayName("shuffle survives replacing the queue")
    fun shuffleSurvivesSetQueue() {
        queue.setQueue(library)
        queue.setShuffle(true)
        assertTrue(queue.isShuffleEnabled())

        // What happens when the user taps a song in the library.
        queue.setQueue(library, startIndex = 3)

        assertTrue(
            queue.isShuffleEnabled(),
            "shuffle was silently switched off by starting a new queue"
        )
    }

    @Test
    @DisplayName("the track the user tapped still plays first when shuffled")
    fun tappedTrackPlaysFirst() {
        queue.setQueue(library)
        queue.setShuffle(true)

        queue.setQueue(library, startIndex = 5)

        assertEquals(library[5], queue.currentTrack)
    }

    @Test
    @DisplayName("a shuffled queue does not play in list order at track ends")
    fun shuffledQueueDoesNotFollowListOrder() {
        // The visible symptom. Ten of twelve tracks in the original order would be
        // an extraordinary coincidence, so this is a fair check without depending
        // on any particular permutation.
        queue.setQueue(library)
        queue.setShuffle(true)
        queue.setQueue(library, startIndex = 0)

        val played = listOf(queue.currentTrack!!) + playThrough(library.size - 1)

        assertEquals(library.size, played.size)
        assertEquals(library.toSet(), played.toSet(), "shuffle must not lose or repeat tracks")
        assertFalse(played == library, "queue played in list order despite shuffle")
    }

    @Test
    @DisplayName("un-shuffling after replacing the queue restores list order")
    fun unshuffleAfterSetQueueRestoresOrder() {
        queue.setQueue(library)
        queue.setShuffle(true)
        queue.setQueue(library, startIndex = 0)

        queue.setShuffle(false)

        assertEquals(library, queue.tracks)
        assertFalse(queue.isShuffleEnabled())
    }

    @Test
    @DisplayName("replacing the queue while not shuffled keeps list order")
    fun setQueueWithoutShuffleIsUnchanged() {
        queue.setQueue(library, startIndex = 2)

        assertEquals(library, queue.tracks)
        assertEquals(library[2], queue.currentTrack)
        assertFalse(queue.isShuffleEnabled())
    }

    // --- Repeat, which is what "or other" covers ---

    @Test
    @DisplayName("repeat all wraps at the end of a shuffled queue")
    fun repeatAllWrapsWhenShuffled() {
        queue.setQueue(library)
        queue.setShuffle(true)
        queue.repeatMode = RepeatMode.ALL

        // Walk past the end; every step must yield a track.
        val played = playThrough(library.size + 3)

        assertEquals(library.size + 3, played.size)
    }

    @Test
    @DisplayName("repeat one stays on the shuffled current track")
    fun repeatOneStaysWhenShuffled() {
        queue.setQueue(library)
        queue.setShuffle(true)
        queue.repeatMode = RepeatMode.ONE

        val current = queue.currentTrack
        assertNotNull(current)
        assertEquals(current, queue.next())
        assertEquals(current, queue.next())
    }

    @Test
    @DisplayName("repeat survives replacing the queue")
    fun repeatSurvivesSetQueue() {
        queue.repeatMode = RepeatMode.ALL
        queue.setQueue(library, startIndex = 0)

        assertEquals(RepeatMode.ALL, queue.repeatMode)
    }

    @Test
    @DisplayName("repeat off still stops at the end of a shuffled queue")
    fun repeatOffStopsWhenShuffled() {
        queue.setQueue(library)
        queue.setShuffle(true)
        queue.repeatMode = RepeatMode.OFF

        // One current track plus size-1 advances exhausts the queue.
        assertEquals(library.size - 1, playThrough(library.size - 1).size)
        assertEquals(null, queue.next(), "repeat off must stop rather than wrap")
    }

    // --- Edge cases ---

    @Test
    @DisplayName("a single-track queue can be shuffled without losing the track")
    fun singleTrackShuffle() {
        queue.setQueue(listOf(library[0]))
        queue.setShuffle(true)
        queue.setQueue(listOf(library[0]))

        assertEquals(listOf(library[0]), queue.tracks)
        assertEquals(library[0], queue.currentTrack)
    }

    @Test
    @DisplayName("replacing a shuffled queue with an empty one is harmless")
    fun emptySetQueueWhileShuffled() {
        queue.setQueue(library)
        queue.setShuffle(true)

        queue.setQueue(emptyList())

        assertTrue(queue.isEmpty)
        assertEquals(null, queue.currentTrack)
        assertEquals(null, queue.next())
    }

    @Test
    @DisplayName("a shuffled queue holds every track exactly once")
    fun shuffleKeepsEveryTrackOnce() {
        queue.setQueue(library)
        queue.setShuffle(true)
        queue.setQueue(library, startIndex = 7)

        assertEquals(library.size, queue.tracks.size)
        assertEquals(library.toSet(), queue.tracks.toSet())
        assertEquals(library.size, queue.tracks.distinct().size)
    }
}
