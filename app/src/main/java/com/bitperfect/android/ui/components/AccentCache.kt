package com.bitperfect.android.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Accent colours already derived from album art, kept for the session.
 *
 * Extracting one means decoding the cover and running Palette over it. That is far
 * too much work to repeat every time a track is revisited — swiping back and forth
 * through a queue would redo it on every swipe, and the accent drives the mini
 * player background, so the cost lands exactly when something is animating.
 *
 * A colour is a few bytes and the answer never changes for a given cover, so this
 * is a plain bounded map rather than anything cleverer. Bounded because a long
 * shuffle through a large library would otherwise hold an entry per track for the
 * life of the process.
 */
internal object AccentCache {

    /**
     * Comfortably more than any queue a user scrolls back and forth through, and
     * still only a few kilobytes.
     */
    private const val MAX_ENTRIES = 128

    private val entries = object : LinkedHashMap<String, Color>(
        /* initialCapacity = */ 32,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?): Boolean =
            size > MAX_ENTRIES
    }

    /** Accent for [artworkUri], or null if it has not been derived yet. */
    fun get(artworkUri: String): Color? = synchronized(entries) { entries[artworkUri] }

    fun put(artworkUri: String, accent: Color) {
        synchronized(entries) { entries[artworkUri] = accent }
    }

    /** Test seam; also used if artwork is rebuilt and colours could be stale. */
    fun clear() = synchronized(entries) { entries.clear() }

    internal fun size(): Int = synchronized(entries) { entries.size }
}
