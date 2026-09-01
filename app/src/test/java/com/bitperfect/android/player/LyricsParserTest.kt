package com.bitperfect.android.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The LRC parser and the position lookup that drives the synced view.
 *
 * These are the only parts of the lyrics feature that can be tested without a
 * device, and they are where the fiddly logic lives — timestamp scaling, repeated
 * timestamps, and picking the current line.
 */
class LyricsParserTest {

    // --- Nothing to show ---

    @Test
    @DisplayName("null, blank and tag-only input produce no lyrics")
    fun emptyInputs() {
        assertTrue(LyricsParser.parse(null).isEmpty)
        assertTrue(LyricsParser.parse("").isEmpty)
        assertTrue(LyricsParser.parse("   \n\n  ").isEmpty)
        // Metadata with no actual lines is still nothing to display.
        assertTrue(LyricsParser.parse("[ti:Song]\n[ar:Artist]").isEmpty)
    }

    // --- Timed (LRC) ---

    @Test
    @DisplayName("timestamped lines parse as synced, in time order")
    fun syncedLines() {
        val lyrics = LyricsParser.parse(
            """
            [00:01.00]First
            [00:05.50]Second
            [01:00.00]Third
            """.trimIndent()
        )

        assertTrue(lyrics.isSynced)
        assertEquals(3, lyrics.lines.size)
        assertEquals(listOf(1_000L, 5_500L, 60_000L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf("First", "Second", "Third"), lyrics.lines.map { it.text })
    }

    @Test
    @DisplayName("fraction digits scale by length, not assumed hundredths")
    fun fractionScaling() {
        // .5 is 500ms, .05 is 50ms, .050 is 50ms. Treating them all as
        // hundredths would put lines up to half a second out.
        val lyrics = LyricsParser.parse("[00:00.5]a\n[00:01.05]b\n[00:02.050]c")
        assertEquals(listOf(500L, 1_050L, 2_050L), lyrics.lines.map { it.timeMs })
    }

    @Test
    @DisplayName("a colon before the fraction is accepted")
    fun colonFraction() {
        assertEquals(listOf(1_500L), LyricsParser.parse("[00:01:50]x").lines.map { it.timeMs })
    }

    @Test
    @DisplayName("minutes past 60 are honoured for long tracks")
    fun longTracks() {
        assertEquals(listOf(75L * 60_000L), LyricsParser.parse("[75:00.00]x").lines.map { it.timeMs })
    }

    @Test
    @DisplayName("a line with repeated timestamps becomes one entry per timestamp")
    fun repeatedTimestamps() {
        // A chorus is written once with several timestamps.
        val lyrics = LyricsParser.parse("[00:10.00][00:40.00][01:10.00]Chorus")

        assertEquals(3, lyrics.lines.size)
        assertEquals(listOf(10_000L, 40_000L, 70_000L), lyrics.lines.map { it.timeMs })
        assertTrue(lyrics.lines.all { it.text == "Chorus" })
    }

    @Test
    @DisplayName("metadata tags are read and not shown as lyrics")
    fun metadataTags() {
        val lyrics = LyricsParser.parse(
            """
            [ti:Some Song]
            [ar:Some Artist]
            [offset:-500]
            [00:01.00]Line
            """.trimIndent()
        )

        assertEquals("Some Song", lyrics.title)
        assertEquals("Some Artist", lyrics.artist)
        assertEquals(-500L, lyrics.offsetMs)
        assertEquals(1, lyrics.lines.size)
        assertEquals("Line", lyrics.lines.first().text)
    }

    @Test
    @DisplayName("an empty timestamped line is kept, so gaps clear the display")
    fun emptyTimedLine() {
        val lyrics = LyricsParser.parse("[00:01.00]Words\n[00:05.00]")
        assertEquals(2, lyrics.lines.size)
        assertEquals("", lyrics.lines[1].text)
    }

    // --- Plain text ---

    @Test
    @DisplayName("text with no timestamps parses as unsynced rather than being rejected")
    fun plainText() {
        val lyrics = LyricsParser.parse("First line\nSecond line\n\nThird line")

        assertFalse(lyrics.isSynced)
        assertEquals(3, lyrics.lines.size)
        assertTrue(lyrics.lines.all { it.timeMs == null })
        assertEquals("First line", lyrics.lines.first().text)
    }

    @Test
    @DisplayName("one timestamped line makes the whole file synced")
    fun mixedPrefersSynced() {
        // Untimed lines are dropped in that case: keeping them would leave lines
        // that can never be selected sitting between ones that can.
        val lyrics = LyricsParser.parse("Header text\n[00:02.00]Timed line")
        assertTrue(lyrics.isSynced)
        assertEquals(listOf("Timed line"), lyrics.lines.map { it.text })
    }

    // --- Position lookup ---

    private val sample = LyricsParser.parse(
        """
        [00:00.00]Zero
        [00:10.00]Ten
        [00:20.00]Twenty
        [00:30.00]Thirty
        """.trimIndent()
    )

    @Test
    @DisplayName("the current line is the last one at or before the position")
    fun indexAtPosition() {
        assertEquals(0, sample.indexAt(0L))
        assertEquals(0, sample.indexAt(9_999L))
        assertEquals(1, sample.indexAt(10_000L))
        assertEquals(2, sample.indexAt(25_000L))
        assertEquals(3, sample.indexAt(30_000L))
        // Past the last line it stays on the last line rather than resetting.
        assertEquals(3, sample.indexAt(600_000L))
    }

    @Test
    @DisplayName("a position before the first line selects nothing")
    fun beforeFirstLine() {
        val later = LyricsParser.parse("[00:05.00]Later")
        assertEquals(-1, later.indexAt(0L))
        assertEquals(-1, later.indexAt(4_999L))
        assertEquals(0, later.indexAt(5_000L))
    }

    @Test
    @DisplayName("unsynced lyrics never select a line")
    fun unsyncedHasNoIndex() {
        val plain = LyricsParser.parse("just words")
        assertEquals(-1, plain.indexAt(1_000L))
        assertEquals(-1, plain.indexAt(999_999L))
    }

    @Test
    @DisplayName("the file offset shifts which line is current")
    fun fileOffsetApplied() {
        // offset:+2000 makes lines appear 2s earlier, so at 8s the 10s line shows.
        val shifted = LyricsParser.parse("[offset:2000]\n[00:00.00]A\n[00:10.00]B")
        assertEquals(1, shifted.indexAt(8_000L))
        assertEquals(0, shifted.indexAt(7_000L))
    }

    @Test
    @DisplayName("a user offset stacks on top of the file offset")
    fun userOffsetApplied() {
        // Without any nudge 9s is still the first line; +1000 pushes it to the second.
        assertEquals(0, sample.indexAt(9_000L))
        assertEquals(1, sample.indexAt(9_000L, userOffsetMs = 1_000L))
        // A negative nudge holds the earlier line for longer.
        assertEquals(0, sample.indexAt(10_500L, userOffsetMs = -1_000L))
    }

    @Test
    @DisplayName("lookup is correct across a long file, where a binary search could slip")
    fun longFileLookup() {
        val text = (0 until 500).joinToString("\n") { i ->
            val minutes = i / 60
            val seconds = i % 60
            "[%02d:%02d.00]line$i".format(minutes, seconds)
        }
        val long = LyricsParser.parse(text)
        assertEquals(500, long.lines.size)

        // Check every line boundary rather than a sample.
        for (i in 0 until 500) {
            assertEquals(i, long.indexAt(i * 1_000L), "at exactly line $i")
            assertEquals(i, long.indexAt(i * 1_000L + 999L), "just before line ${i + 1}")
        }
    }
}
