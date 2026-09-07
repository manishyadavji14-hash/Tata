package com.bitperfect.android.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The A-Z jump strip's contents.
 *
 * Requested as "a to z short key in right side of library (if songs are sorted by
 * name)". The behaviour worth pinning down is that the strip is built from the list
 * rather than from a fixed alphabet: every label is then a row that exists, and a
 * Z-A sort produces a Z-A strip with no special case.
 */
@DisplayName("AlphabetIndex Tests")
class AlphabetIndexTest {

    private fun lettersOf(entries: List<AlphabetIndex.Entry>) =
        entries.map { it.letter }.joinToString("")

    // --- Building the strip ---

    @Test
    @DisplayName("one entry per letter, pointing at that letter's first row")
    fun buildsOneEntryPerLetter() {
        val entries = AlphabetIndex.build(
            listOf("Aja", "Angie", "Blackbird", "Cocaine", "Coma")
        )

        assertEquals("ABC", lettersOf(entries))
        assertEquals(listOf(0, 2, 3), entries.map { it.itemIndex })
    }

    @Test
    @DisplayName("a Z-A list produces a Z-A strip, with no reversing anywhere")
    fun descendingListGivesDescendingStrip() {
        // The reason the strip is built from list order: the sort direction never
        // has to be plumbed down here, and it cannot get out of step with the rows.
        val entries = AlphabetIndex.build(listOf("Zoo", "Yes", "Xanadu", "Wish"))

        assertEquals("ZYXW", lettersOf(entries))
    }

    @Test
    @DisplayName("letters absent from the list are not offered")
    fun skipsAbsentLetters() {
        // A label for a letter no track starts with would scroll nowhere.
        val entries = AlphabetIndex.build(listOf("Aja", "Zoo"))

        assertEquals("AZ", lettersOf(entries))
    }

    @Test
    @DisplayName("an empty list has no strip")
    fun emptyList() {
        assertTrue(AlphabetIndex.build(emptyList<String>()).isEmpty())
    }

    @Test
    @DisplayName("case does not split a letter in two")
    fun caseInsensitive() {
        val entries = AlphabetIndex.build(listOf("abbey road", "Aja"))

        assertEquals("A", lettersOf(entries))
        assertEquals(0, entries.single().itemIndex)
    }

    // --- Bucketing ---

    @Test
    @DisplayName("leading punctuation does not decide the letter")
    fun ignoresLeadingPunctuation() {
        // Otherwise every quoted or bracketed title lands in one bucket.
        assertEquals('H', AlphabetIndex.bucketOf("\"Heroes\""))
        assertEquals('D', AlphabetIndex.bucketOf("(Don't Fear) The Reaper"))
        assertEquals('S', AlphabetIndex.bucketOf("   Something"))
    }

    @Test
    @DisplayName("digits and symbols share one bucket")
    fun digitsAndSymbolsBucketTogether() {
        // Ten digit labels would crowd the letters off a phone screen.
        assertEquals(AlphabetIndex.OTHER, AlphabetIndex.bucketOf("1979"))
        assertEquals(AlphabetIndex.OTHER, AlphabetIndex.bucketOf("99 Problems"))
        assertEquals(AlphabetIndex.OTHER, AlphabetIndex.bucketOf("!!!"))
        assertEquals(AlphabetIndex.OTHER, AlphabetIndex.bucketOf(""))
    }

    @Test
    @DisplayName("a non-Latin title keeps its own letter")
    fun nonLatinScriptsAreNotLumpedTogether() {
        // A library is not necessarily in English; folding these into "#" would put
        // most of such a library under one unusable label.
        val entries = AlphabetIndex.build(listOf("आजा", "बरसात", "Cocaine"))

        assertEquals(3, entries.size)
        assertEquals('C', entries.last().letter)
        assertTrue(entries[0].letter != AlphabetIndex.OTHER, "Devanagari fell into the # bucket")
    }

    // --- Fitting a strip that is too long ---

    @Test
    @DisplayName("a strip that fits is left alone")
    fun fitKeepsShortStrips() {
        val entries = AlphabetIndex.build(listOf("Aja", "Blackbird", "Coma"))

        assertEquals(entries, AlphabetIndex.fit(entries, maxLabels = 10))
    }

    @Test
    @DisplayName("a strip too long to aim at is thinned, keeping both ends")
    fun fitThinsLongStrips() {
        val entries = ('A'..'Z').mapIndexed { index, letter ->
            AlphabetIndex.Entry(letter, index)
        }

        val fitted = AlphabetIndex.fit(entries, maxLabels = 6)

        assertTrue(fitted.size <= 6, "kept ${fitted.size} labels for a budget of 6")
        // The ends must survive: they are how the list's start and end are reached.
        assertEquals('A', fitted.first().letter)
        assertEquals('Z', fitted.last().letter)
        // Still in order, so the strip still reads top-to-bottom.
        assertEquals(fitted.sortedBy { it.itemIndex }, fitted)
    }

    @Test
    @DisplayName("thinning never repeats a label")
    fun fitDoesNotDuplicate() {
        val entries = ('A'..'J').mapIndexed { index, letter ->
            AlphabetIndex.Entry(letter, index)
        }

        for (budget in 1..12) {
            val fitted = AlphabetIndex.fit(entries, budget)
            assertEquals(
                fitted.distinct().size,
                fitted.size,
                "budget=$budget produced a duplicate label"
            )
        }
    }

    @Test
    @DisplayName("no room means no strip")
    fun fitWithNoRoom() {
        val entries = AlphabetIndex.build(listOf("Aja"))

        assertTrue(AlphabetIndex.fit(entries, maxLabels = 0).isEmpty())
        assertTrue(AlphabetIndex.fit(entries, maxLabels = -1).isEmpty())
    }

    // --- Mapping a touch to a label ---

    @Test
    @DisplayName("a touch maps to the label under it")
    fun labelAtMapsAcrossTheStrip() {
        assertEquals(0, AlphabetIndex.labelAt(0f, count = 4))
        assertEquals(1, AlphabetIndex.labelAt(0.3f, count = 4))
        assertEquals(2, AlphabetIndex.labelAt(0.6f, count = 4))
        assertEquals(3, AlphabetIndex.labelAt(0.99f, count = 4))
    }

    @Test
    @DisplayName("a drag past either end keeps pointing at the nearest label")
    fun labelAtClamps() {
        // A drag routinely continues past the strip; it must not stop responding or
        // index out of bounds.
        assertEquals(0, AlphabetIndex.labelAt(-2f, count = 5))
        assertEquals(4, AlphabetIndex.labelAt(1f, count = 5))
        assertEquals(4, AlphabetIndex.labelAt(9f, count = 5))
    }

    @Test
    @DisplayName("an empty strip has no label to hit")
    fun labelAtEmpty() {
        assertEquals(-1, AlphabetIndex.labelAt(0.5f, count = 0))
    }
}
