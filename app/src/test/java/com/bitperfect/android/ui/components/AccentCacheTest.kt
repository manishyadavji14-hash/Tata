package com.bitperfect.android.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Remembering accent colours so a track change does not redo the work.
 *
 * Deriving one decodes the cover and runs Palette over it. Without this, swiping
 * back and forth through a queue repeats that on every swipe — and the accent drives
 * the mini player background, so the cost lands precisely while something is
 * animating.
 */
@DisplayName("AccentCache Tests")
class AccentCacheTest {

    @BeforeEach
    fun reset() = AccentCache.clear()

    @Test
    @DisplayName("a derived colour is returned again without recomputing")
    fun storesAndReturns() {
        AccentCache.put("art://1", Color.Red)

        assertEquals(Color.Red, AccentCache.get("art://1"))
    }

    @Test
    @DisplayName("an unknown cover has no colour yet")
    fun missIsNull() {
        assertNull(AccentCache.get("art://never-seen"))
    }

    @Test
    @DisplayName("colours are per cover, not shared")
    fun keyedPerCover() {
        AccentCache.put("art://1", Color.Red)
        AccentCache.put("art://2", Color.Blue)

        assertEquals(Color.Red, AccentCache.get("art://1"))
        assertEquals(Color.Blue, AccentCache.get("art://2"))
    }

    @Test
    @DisplayName("a long shuffle cannot grow the cache without bound")
    fun boundedSize() {
        // Otherwise a shuffle through a large library holds an entry per track for
        // the life of the process.
        repeat(1_000) { index -> AccentCache.put("art://$index", Color.Green) }

        assertTrue(
            AccentCache.size() <= 128,
            "cache grew to ${AccentCache.size()} entries"
        )
    }

    @Test
    @DisplayName("eviction drops the least recently used, not the most recent")
    fun evictsLeastRecentlyUsed() {
        repeat(200) { index -> AccentCache.put("art://$index", Color.Green) }

        // The newest entry must survive: it is the track playing right now.
        assertEquals(Color.Green, AccentCache.get("art://199"))
        // And the oldest must not, or nothing was evicted at all.
        assertNull(AccentCache.get("art://0"))
    }

    @Test
    @DisplayName("reading an entry keeps it alive")
    fun readCountsAsUse() {
        AccentCache.put("art://keep", Color.Red)
        repeat(100) { index -> AccentCache.put("art://filler$index", Color.Green) }

        // Touch it, then push the cache well past its limit.
        assertEquals(Color.Red, AccentCache.get("art://keep"))
        repeat(100) { index -> AccentCache.put("art://more$index", Color.Blue) }

        assertEquals(
            Color.Red,
            AccentCache.get("art://keep"),
            "a colour in active use was evicted while unused ones survived"
        )
    }

    @Test
    @DisplayName("clearing empties it, for when artwork is rebuilt")
    fun clearEmpties() {
        AccentCache.put("art://1", Color.Red)

        AccentCache.clear()

        assertNull(AccentCache.get("art://1"))
        assertEquals(0, AccentCache.size())
    }
}
