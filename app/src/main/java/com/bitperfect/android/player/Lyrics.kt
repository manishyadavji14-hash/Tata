package com.bitperfect.android.player

/**
 * Lyrics for a track, either timed or plain.
 *
 * Timed lyrics follow playback; plain lyrics are scrolled by the user. Which of
 * the two you get is decided entirely by whether the source carried timestamps,
 * so the UI does not have to guess.
 */
data class Lyrics(
    val lines: List<Line>,
    /**
     * True when at least one line carried a timestamp, so playback position can
     * pick the current line.
     */
    val isSynced: Boolean,
    /** Optional metadata from LRC tags. */
    val title: String = "",
    val artist: String = "",
    /**
     * Offset in milliseconds declared by an LRC `[offset:]` tag. Positive values
     * mean the lyrics should appear earlier.
     */
    val offsetMs: Long = 0L
) {
    data class Line(
        /** Milliseconds from the start of the track, or null for plain lyrics. */
        val timeMs: Long?,
        val text: String
    )

    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Index of the line that should be highlighted at [positionMs], or -1 before
     * the first timed line.
     *
     * Uses a binary search rather than a scan: this is called on every position
     * tick, four times a second, and a long song can carry hundreds of lines.
     *
     * @param userOffsetMs extra nudge applied on top of the file's own offset,
     *   for when a file's timings are early or late.
     */
    fun indexAt(positionMs: Long, userOffsetMs: Long = 0L): Int {
        if (!isSynced) return -1

        val target = positionMs + offsetMs + userOffsetMs

        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            val time = lines[mid].timeMs
            if (time == null) {
                // Untimed line inside a synced file: skip it by moving past.
                low = mid + 1
                continue
            }
            if (time <= target) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        val EMPTY = Lyrics(lines = emptyList(), isSynced = false)
    }
}

/**
 * Parser for LRC and plain-text lyrics.
 *
 * LRC is the de facto format for synced lyrics: each line is prefixed with one or
 * more `[mm:ss.cc]` timestamps, optionally preceded by `[ti:]`, `[ar:]` and
 * `[offset:]` metadata tags.
 *
 * Anything without timestamps is returned as plain lyrics rather than rejected,
 * so a `.txt` of the words still displays — it just cannot follow playback.
 *
 * Pure and side-effect free, so it is fully unit tested.
 */
object LyricsParser {

    /**
     * Matches one timestamp: [mm:ss], [mm:ss.cc] or [mm:ss.ccc], also accepting
     * a colon before the fraction, which some writers emit.
     */
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Matches an `[id:value]` metadata tag, e.g. `[ti:Song]`. */
    private val METADATA = Regex("""^\[([a-zA-Z]+):(.*)]$""")

    fun parse(raw: String?): Lyrics {
        if (raw.isNullOrBlank()) return Lyrics.EMPTY

        val timed = mutableListOf<Lyrics.Line>()
        val plain = mutableListOf<String>()
        var title = ""
        var artist = ""
        var offsetMs = 0L

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // A metadata tag only counts when it is the whole line and its key is
            // not a timestamp, so "[ar:x]" is metadata but "[00:12.00]" is not.
            val metadata = METADATA.matchEntire(line)
            if (metadata != null && !TIMESTAMP.matches(line)) {
                val key = metadata.groupValues[1].lowercase()
                val value = metadata.groupValues[2].trim()
                when (key) {
                    "ti" -> title = value
                    "ar" -> artist = value
                    "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                }
                continue
            }

            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty()) {
                plain.add(line)
                continue
            }

            // The text is whatever follows the final timestamp. A line may carry
            // several timestamps when the same words repeat, so each produces its
            // own entry.
            val text = line.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                timed.add(Lyrics.Line(timeMs = toMillis(stamp), text = text))
            }
        }

        return if (timed.isNotEmpty()) {
            Lyrics(
                // Sort by time: repeated-timestamp lines are emitted out of order,
                // and indexAt() binary searches, which requires sorted input.
                lines = timed.sortedBy { it.timeMs ?: Long.MAX_VALUE },
                isSynced = true,
                title = title,
                artist = artist,
                offsetMs = offsetMs
            )
        } else if (plain.isNotEmpty()) {
            Lyrics(
                lines = plain.map { Lyrics.Line(timeMs = null, text = it) },
                isSynced = false,
                title = title,
                artist = artist
            )
        } else {
            Lyrics.EMPTY
        }
    }

    private fun toMillis(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fractionText = match.groupValues[3]

        // ".5" is 500 ms, ".05" is 50 ms, ".050" is also 50 ms. Scale by digit
        // count rather than assuming hundredths.
        val fraction = when (fractionText.length) {
            0 -> 0L
            1 -> (fractionText.toLongOrNull() ?: 0L) * 100L
            2 -> (fractionText.toLongOrNull() ?: 0L) * 10L
            else -> fractionText.take(3).toLongOrNull() ?: 0L
        }

        return minutes * 60_000L + seconds * 1_000L + fraction
    }
}
