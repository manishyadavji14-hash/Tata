package com.bitperfect.android.ui.library

/**
 * The A-Z strip's contents: which letters a name-sorted list actually contains,
 * and where each one starts.
 *
 * Built from the list rather than from a fixed A-Z alphabet, for two reasons:
 *
 * - a letter no track starts with would be a dead target, and in a library of a
 *   few hundred tracks most of the alphabet is usually dead;
 * - **it makes Z-A work for free.** Entries keep the order they appear in, so a
 *   descending sort produces a descending strip with no special case anywhere.
 *   Building from a fixed alphabet would have needed the sort direction plumbed
 *   down here, and would have been wrong for any list that is not Latin.
 *
 * The bucketing keeps non-Latin scripts intact — a Devanagari title indexes under
 * its own first letter rather than being lumped into "other" — because a library is
 * not necessarily in English.
 */
object AlphabetIndex {

    /** A jump target: [letter] is the label, [itemIndex] the row to scroll to. */
    data class Entry(val letter: Char, val itemIndex: Int)

    /**
     * Label for names that begin with a digit or a symbol.
     *
     * One bucket for both: a strip with ten digit labels crowds out the letters,
     * and "starts with something that is not a letter" is a single useful idea.
     */
    const val OTHER = '#'

    /**
     * Jump targets for [names], in list order, one per distinct letter.
     *
     * @param names the visible rows' sort keys, in the order they are displayed.
     */
    fun build(names: List<String>): List<Entry> {
        val entries = mutableListOf<Entry>()
        val seen = mutableSetOf<Char>()

        names.forEachIndexed { index, name ->
            val letter = bucketOf(name)
            if (seen.add(letter)) entries += Entry(letter = letter, itemIndex = index)
        }

        return entries
    }

    /**
     * The letter [name] belongs under.
     *
     * The first *letter or digit* decides it, so leading punctuation and quotes do
     * not send half a library into one bucket — `"Heroes"` indexes under H, and
     * `(Don't Fear) The Reaper` under D.
     */
    fun bucketOf(name: String): Char {
        val character = name.firstOrNull { it.isLetterOrDigit() } ?: return OTHER
        return if (character.isLetter()) character.uppercaseChar() else OTHER
    }

    /**
     * At most [maxLabels] of [entries], evenly spaced.
     *
     * A strip taller than the screen cannot be aimed at, and mixed scripts can
     * easily produce more letters than fit. Dropping some labels keeps every
     * remaining one a real, reachable target — always including the first and last
     * so the ends of the list stay one gesture away.
     */
    fun fit(entries: List<Entry>, maxLabels: Int): List<Entry> {
        if (maxLabels <= 0) return emptyList()
        if (entries.size <= maxLabels) return entries
        if (maxLabels == 1) return listOf(entries.first())

        val step = (entries.size - 1).toDouble() / (maxLabels - 1)
        // Distinct because rounding can land twice on the same entry.
        return (0 until maxLabels)
            .map { entries[Math.round(it * step).toInt()] }
            .distinct()
    }

    /**
     * Which of [count] labels a touch [fraction] of the way down the strip picks.
     *
     * @param fraction 0 at the top of the strip, 1 at the bottom. Values outside
     *   that are clamped, because a drag routinely continues past either end and
     *   should keep pointing at the nearest label rather than stop responding.
     */
    fun labelAt(fraction: Float, count: Int): Int {
        if (count <= 0) return -1
        val scaled = (fraction * count).toInt()
        return scaled.coerceIn(0, count - 1)
    }
}
