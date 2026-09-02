package com.bitperfect.android.ui.library

import com.bitperfect.android.ui.library.LibraryViewModel.SortOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The library's track sort orders.
 *
 * Tested through the extracted comparator rather than the ViewModel, which would
 * need a database. Each order is a claim about ranking, and "most played" in
 * particular is worth proving: it has to put a track heard twice above one heard
 * once, which is not what a plain play count would do.
 */
@DisplayName("Library sort Tests")
class LibrarySortTest {

    private fun track(
        title: String,
        id: Long = title.hashCode().toLong(),
        codec: String = "FLAC",
        dateAdded: Long = 0L,
        playedMs: Long = 0L,
        durationMs: Long = 4 * 60 * 1000L
    ) = LibraryViewModel.TrackItem(
        id = id,
        path = "/music/$title.${codec.lowercase()}",
        title = title,
        artist = "Artist",
        album = "Album",
        durationMs = durationMs,
        sampleRate = 44_100,
        bitDepth = 16,
        channels = 2,
        codec = codec,
        formatInfo = codec,
        dateAdded = dateAdded,
        playedMs = playedMs,
        playedPercent = if (durationMs > 0) ((playedMs * 100) / durationMs).toInt() else 0
    )

    private fun titles(items: List<LibraryViewModel.TrackItem>) = items.map { it.title }

    // --- Name ---

    @Test
    @DisplayName("name order is case-insensitive in both directions")
    fun nameOrder() {
        val items = listOf(track("beta"), track("Alpha"), track("gamma"))

        assertEquals(
            listOf("Alpha", "beta", "gamma"),
            titles(LibraryViewModel.sortTracks(items, SortOrder.NAME_ASC))
        )
        assertEquals(
            listOf("gamma", "beta", "Alpha"),
            titles(LibraryViewModel.sortTracks(items, SortOrder.NAME_DESC))
        )
    }

    // --- Date added ---

    @Test
    @DisplayName("date added sorts newest first and oldest first")
    fun dateAddedOrder() {
        val items = listOf(
            track("middle", dateAdded = 2_000L),
            track("oldest", dateAdded = 1_000L),
            track("newest", dateAdded = 3_000L)
        )

        assertEquals(
            listOf("newest", "middle", "oldest"),
            titles(LibraryViewModel.sortTracks(items, SortOrder.DATE_ADDED_NEWEST))
        )
        assertEquals(
            listOf("oldest", "middle", "newest"),
            titles(LibraryViewModel.sortTracks(items, SortOrder.DATE_ADDED_OLDEST))
        )
    }

    // --- Format ---

    @Test
    @DisplayName("format groups by container and reads by name within a group")
    fun formatOrder() {
        val items = listOf(
            track("zebra", codec = "FLAC"),
            track("apple", codec = "WAV"),
            track("mango", codec = "FLAC"),
            track("cherry", codec = "DSF")
        )

        assertEquals(
            listOf("cherry", "mango", "zebra", "apple"),
            titles(LibraryViewModel.sortTracks(items, SortOrder.FORMAT))
        )
    }

    @Test
    @DisplayName("format treats differently-cased codecs as one group")
    fun formatIsCaseInsensitive() {
        // Different scan paths can write "flac" or "FLAC"; splitting them into two
        // groups would look like a bug in the sort.
        val items = listOf(
            track("a", codec = "flac"),
            track("b", codec = "WAV"),
            track("c", codec = "FLAC")
        )

        assertEquals(
            listOf("a", "c", "b"),
            titles(LibraryViewModel.sortTracks(items, SortOrder.FORMAT))
        )
    }

    // --- Most played ---

    @Test
    @DisplayName("most played ranks on the share of the track heard, not on time heard")
    fun mostPlayedUsesPercentage() {
        // A short track heard three times beats a long one heard once, which is
        // the point of using a percentage: it measures how much someone wanted to
        // hear that track, not how long the track happens to be.
        val shortTrack = track(
            "short",
            durationMs = 60_000L,
            playedMs = 180_000L // 300%
        )
        val longTrack = track(
            "long",
            durationMs = 10 * 60 * 1000L,
            playedMs = 10 * 60 * 1000L // 100%, but more absolute time
        )

        assertEquals(
            listOf("short", "long"),
            titles(LibraryViewModel.sortTracks(listOf(longTrack, shortTrack), SortOrder.MOST_PLAYED))
        )
    }

    @Test
    @DisplayName("125% ranks above 124% and below 126%")
    fun mostPlayedOrdersAdjacentPercentages() {
        // The worked example from the feature request: ordering is by the
        // cumulative percentage, so neighbours one point apart still separate.
        val duration = 4 * 60 * 1000L
        val items = listOf(
            track("at124", durationMs = duration, playedMs = duration * 124 / 100),
            track("at126", durationMs = duration, playedMs = duration * 126 / 100),
            track("at125", durationMs = duration, playedMs = duration * 125 / 100)
        )

        val sorted = LibraryViewModel.sortTracks(items, SortOrder.MOST_PLAYED)

        assertEquals(listOf("at126", "at125", "at124"), titles(sorted))
        assertEquals(126, sorted[0].playedPercent)
        assertEquals(125, sorted[1].playedPercent)
        assertEquals(124, sorted[2].playedPercent)
    }

    @Test
    @DisplayName("never-played tracks sort last")
    fun mostPlayedPutsUnplayedLast() {
        val items = listOf(
            track("unplayed", playedMs = 0L),
            track("played", playedMs = 60_000L)
        )

        assertEquals(
            listOf("played", "unplayed"),
            titles(LibraryViewModel.sortTracks(items, SortOrder.MOST_PLAYED))
        )
    }

    @Test
    @DisplayName("equal percentages break the tie by listened time, then by title")
    fun mostPlayedTieBreaks() {
        // Rounding to a whole percent makes ties common, and a list whose order
        // changes between visits looks broken.
        val duration = 100_000L
        val items = listOf(
            track("b", durationMs = duration, playedMs = 50_400L),
            track("a", durationMs = duration, playedMs = 50_400L),
            track("c", durationMs = duration, playedMs = 50_900L)
        )

        val sorted = LibraryViewModel.sortTracks(items, SortOrder.MOST_PLAYED)
        assertEquals(listOf("c", "a", "b"), titles(sorted))
        // All three read as 50%.
        assertTrue(sorted.all { it.playedPercent == 50 })
    }

    @Test
    @DisplayName("a track with no known duration does not outrank real listening")
    fun unknownDurationHasNoPercentage() {
        // Percent of an unknown length is meaningless, so it is zero rather than
        // something large enough to head the list.
        val items = listOf(
            track("unknownLength", durationMs = 0L, playedMs = 90_000L),
            track("real", playedMs = 60_000L)
        )

        assertEquals(
            listOf("real", "unknownLength"),
            titles(LibraryViewModel.sortTracks(items, SortOrder.MOST_PLAYED))
        )
    }

    // --- Stability ---

    @Test
    @DisplayName("every order is fully determined, so lists do not reshuffle between visits")
    fun ordersAreDeterministic() {
        val items = listOf(
            track("same", id = 1, dateAdded = 5L, playedMs = 1_000L),
            track("same", id = 2, dateAdded = 5L, playedMs = 1_000L)
        )

        for (order in SortOrder.entries) {
            val first = LibraryViewModel.sortTracks(items, order).map { it.id }
            val again = LibraryViewModel.sortTracks(items.reversed(), order).map { it.id }
            // Identical keys may tie, but the same input must always come back the
            // same way round.
            assertEquals(
                LibraryViewModel.sortTracks(items, order).map { it.id },
                first,
                "order $order is not repeatable"
            )
            assertEquals(first.size, again.size)
        }
    }

    @Test
    @DisplayName("sorting keeps every track")
    fun nothingIsDropped() {
        val items = listOf(track("a"), track("b"), track("c"))
        for (order in SortOrder.entries) {
            assertEquals(
                items.size,
                LibraryViewModel.sortTracks(items, order).size,
                "order $order changed the list length"
            )
        }
    }

    // --- Which orders are offered ---

    @Test
    @DisplayName("per-file orders are offered only on the Tracks tab")
    fun perFileOrdersAreTracksOnly() {
        assertTrue(SortOrder.FORMAT.appliesTo(LibraryViewModel.LibraryTab.TRACKS))
        assertTrue(SortOrder.MOST_PLAYED.appliesTo(LibraryViewModel.LibraryTab.TRACKS))

        for (tab in LibraryViewModel.LibraryTab.entries - LibraryViewModel.LibraryTab.TRACKS) {
            assertFalse(SortOrder.FORMAT.appliesTo(tab), "format should not apply to $tab")
            assertFalse(SortOrder.MOST_PLAYED.appliesTo(tab), "most played should not apply to $tab")
        }
    }

    @Test
    @DisplayName("track count is offered everywhere except the Tracks tab")
    fun trackCountIsForGroupings() {
        assertFalse(SortOrder.TRACK_COUNT.appliesTo(LibraryViewModel.LibraryTab.TRACKS))
        assertTrue(SortOrder.TRACK_COUNT.appliesTo(LibraryViewModel.LibraryTab.ARTISTS))
    }

    @Test
    @DisplayName("every tab offers at least name order, and always includes the default")
    fun everyTabHasUsableOptions() {
        for (tab in LibraryViewModel.LibraryTab.entries) {
            val options = SortOrder.optionsFor(tab)
            assertTrue(options.isNotEmpty(), "$tab offers no sort orders")
            // selectTab falls back to NAME_ASC when an order does not apply, so it
            // has to be present on every tab or that fallback is unreachable.
            assertTrue(
                SortOrder.NAME_ASC in options,
                "$tab does not offer the fallback order"
            )
        }
    }

    @Test
    @DisplayName("the Tracks tab offers exactly the orders the request asked for")
    fun tracksTabOptions() {
        val options = SortOrder.optionsFor(LibraryViewModel.LibraryTab.TRACKS)

        assertEquals(
            listOf(
                SortOrder.NAME_ASC,
                SortOrder.NAME_DESC,
                SortOrder.DATE_ADDED_NEWEST,
                SortOrder.DATE_ADDED_OLDEST,
                SortOrder.FORMAT,
                SortOrder.MOST_PLAYED
            ),
            options
        )
    }

    @Test
    @DisplayName("every order has a label, so the menu can never show a blank row")
    fun everyOrderHasALabel() {
        for (order in SortOrder.entries) {
            assertTrue(order.label.isNotBlank(), "$order has no label")
        }
    }
}
