package com.bitperfect.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Sizing a cover so it can travel with a media session update.
 *
 * Reported as "album art is not showing in notification panel and lock screen" —
 * still, after three earlier artwork fixes, and while the same covers displayed
 * inside the app.
 *
 * Two faults here, and they compound. The decode was capped at "at least 512px on
 * both edges" rather than "at most 512px on the longest", so a 1000x1000 cover
 * decoded at full size — four times the pixels. The encode then made one attempt and
 * returned **null** if the JPEG came out over the 512 KB session budget, silently,
 * with no log and no retry. Together: a perfectly good cover, read and decoded, that
 * never reached the session and left the lock screen blank with nothing to explain
 * it.
 *
 * Only the arithmetic is tested here. Decoding needs a real `Bitmap`, and there is
 * no `androidTest` source set — see HANDOFF.md section 4.
 */
@DisplayName("ArtworkLoader Tests")
class ArtworkLoaderTest {

    private val maxEdge = ArtworkLoader.MAX_EDGE_PX

    /** Longest edge after applying [sample]. */
    private fun decodedLongestEdge(width: Int, height: Int, sample: Int) =
        maxOf(width, height) / sample

    // --- The cap that was not a cap ---

    @Test
    @DisplayName("a 1000px cover is halved, not decoded at full size")
    fun capsTheCommonCase() {
        // The old rule halved only while *both* halved edges were still >= 512, so
        // 1000/2 = 500 failed the test and the sample stayed at 1: a 1000px bitmap,
        // whose JPEG could exceed the byte budget and be thrown away.
        assertEquals(2, ArtworkLoader.sampleSizeFor(1000, 1000))
        assertEquals(500, decodedLongestEdge(1000, 1000, 2))
    }

    @Test
    @DisplayName("the longest edge never exceeds the cap, over a wide range of sizes")
    fun neverExceedsTheCap() {
        val sizes = listOf(
            64 to 64, 300 to 300, 500 to 500, 512 to 512, 513 to 513,
            600 to 600, 1000 to 1000, 1024 to 1024, 1400 to 1400,
            2000 to 2000, 3000 to 3000, 6000 to 6000
        )

        for ((width, height) in sizes) {
            val sample = ArtworkLoader.sampleSizeFor(width, height)
            val longest = decodedLongestEdge(width, height, sample)

            assertTrue(
                longest <= maxEdge,
                "${width}x$height decoded at $longest px, above the $maxEdge px cap"
            )
        }
    }

    @Test
    @DisplayName("a wide cover is reduced too, rather than blocked by its short edge")
    fun wideCoversAreReduced() {
        // The old rule required *both* edges to stay large, so a 2000x400 banner
        // could not be reduced at all and decoded at its full 2000px width.
        val sample = ArtworkLoader.sampleSizeFor(2000, 400)

        assertTrue(sample > 1, "a 2000x400 cover was not reduced at all")
        assertTrue(decodedLongestEdge(2000, 400, sample) <= maxEdge)
    }

    @Test
    @DisplayName("a cover already within the cap is decoded as it is")
    fun smallCoversAreUntouched() {
        assertEquals(1, ArtworkLoader.sampleSizeFor(512, 512))
        assertEquals(1, ArtworkLoader.sampleSizeFor(300, 200))
    }

    @Test
    @DisplayName("only powers of two are returned")
    fun powersOfTwoOnly() {
        // BitmapFactory rounds inSampleSize down to a power of two, so anything else
        // would silently decode larger than intended.
        for (edge in listOf(600, 900, 1500, 2600, 4000, 7000)) {
            val sample = ArtworkLoader.sampleSizeFor(edge, edge)
            assertEquals(
                0,
                sample and (sample - 1),
                "sampleSizeFor($edge) returned $sample, which is not a power of two"
            )
        }
    }

    @Test
    @DisplayName("unknown bounds decode at full size rather than dividing by zero")
    fun unreadableBounds() {
        // BitmapFactory reports -1 when it could not read the header.
        assertEquals(1, ArtworkLoader.sampleSizeFor(0, 0))
        assertEquals(1, ArtworkLoader.sampleSizeFor(-1, -1))
        assertEquals(1, ArtworkLoader.sampleSizeFor(100, 0))
    }

    // --- Degrading instead of dropping ---

    @Test
    @DisplayName("the first attempt is full quality at full resolution")
    fun ladderStartsAtBestQuality() {
        val first = ArtworkLoader.compressLadder().first()

        assertEquals(ArtworkLoader.JPEG_QUALITY, first.quality)
        assertEquals(1, first.scale)
    }

    @Test
    @DisplayName("quality is given up before resolution")
    fun ladderPrefersQualityLossOverScaling() {
        val ladder = ArtworkLoader.compressLadder()
        val firstScaled = ladder.indexOfFirst { it.scale > 1 }
        val lastFullSize = ladder.indexOfLast { it.scale == 1 }

        assertTrue(firstScaled > lastFullSize, "the ladder scales before dropping quality")
    }

    @Test
    @DisplayName("the ladder ends smaller than it starts, and is finite")
    fun ladderTerminatesAtSomethingSmaller() {
        val ladder = ArtworkLoader.compressLadder()
        val last = ladder.last()

        assertTrue(ladder.size in 2..40, "ladder has ${ladder.size} steps")
        assertTrue(last.scale > 1, "the last attempt does not reduce resolution")
        assertTrue(last.quality < ArtworkLoader.JPEG_QUALITY)
    }

    @Test
    @DisplayName("every attempt is a usable JPEG quality and a positive divisor")
    fun ladderValuesAreSane() {
        for (attempt in ArtworkLoader.compressLadder()) {
            assertTrue(
                attempt.quality in 1..100,
                "quality ${attempt.quality} is not a valid JPEG quality"
            )
            assertTrue(attempt.scale >= 1, "scale ${attempt.scale} would enlarge the cover")
        }
    }

    @Test
    @DisplayName("no attempt is tried twice")
    fun ladderHasNoDuplicates() {
        val ladder = ArtworkLoader.compressLadder()

        assertEquals(ladder.distinct().size, ladder.size, "the ladder repeats an attempt")
    }
}
