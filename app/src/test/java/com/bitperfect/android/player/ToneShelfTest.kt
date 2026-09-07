package com.bitperfect.android.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The bass and treble shelves.
 *
 * Reported as "bass boost and treble not working". Treble genuinely did almost
 * nothing: it put the full control on the top band and half on the one below, and
 * on the five-band equalizer nearly every Android device exposes, the top band is
 * centred at about 14 kHz — where there is very little musical content. Choosing
 * bands by frequency instead of by index is what makes the control audible, and it
 * also behaves correctly on the ten-band equalizers some devices report.
 *
 * Tested through the pure shaping function, since everything else in
 * `AudioEffectsController` needs a real AudioTrack session.
 */
@DisplayName("Tone shelf Tests")
class ToneShelfTest {

    /** What almost every Android device reports. */
    private val fiveBand = listOf(60, 230, 910, 3_600, 14_000).mapIndexed { index, hz ->
        AudioEffectsController.Band(index = index, centerFrequencyHz = hz)
    }

    /** A ten-band device, to prove the rule is not tied to a band count. */
    private val tenBand =
        listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
            .mapIndexed { index, hz ->
                AudioEffectsController.Band(index = index, centerFrequencyHz = hz)
            }

    private val maxLevel = 1_500 // millibels, i.e. +15 dB

    private fun treble(bands: List<AudioEffectsController.Band>, strength: Int) =
        AudioEffectsController.shelfLevels(bands, maxLevel, strength, high = true)

    private fun bass(bands: List<AudioEffectsController.Band>, strength: Int) =
        AudioEffectsController.shelfLevels(bands, maxLevel, strength, high = false)

    // --- Treble ---

    @Test
    @DisplayName("treble lifts every band from 2 kHz up, not just the top one")
    fun trebleCoversTheTrebleRange() {
        val levels = treble(fiveBand, AudioEffectsController.MAX_STRENGTH)

        // 60, 230, 910 Hz are untouched; 3.6 kHz and 14 kHz are lifted.
        assertEquals(0, levels[0])
        assertEquals(0, levels[1])
        assertEquals(0, levels[2])
        assertTrue(levels[3] > 0, "3.6 kHz must be lifted; this is the audible part")
        assertTrue(levels[4] > 0, "14 kHz must be lifted")
    }

    @Test
    @DisplayName("treble rises towards the top of the spectrum")
    fun trebleRamps() {
        val levels = treble(fiveBand, AudioEffectsController.MAX_STRENGTH)

        assertTrue(levels[4] > levels[3], "the shelf should rise, not be flat")
        assertEquals(maxLevel, levels[4], "the top band reaches the full control")
    }

    @Test
    @DisplayName("treble on a ten-band device still starts at 2 kHz")
    fun trebleOnTenBands() {
        val levels = treble(tenBand, AudioEffectsController.MAX_STRENGTH)

        // 31 Hz to 1 kHz untouched, 2 kHz upward lifted.
        assertTrue(levels.take(6).all { it == 0 }, "bands below 2 kHz must be untouched")
        assertTrue(levels.drop(6).all { it > 0 }, "every band from 2 kHz up must be lifted")
        assertEquals(maxLevel, levels.last())
    }

    @Test
    @DisplayName("treble scales with the control")
    fun trebleScales() {
        val half = treble(fiveBand, AudioEffectsController.MAX_STRENGTH / 2)
        val full = treble(fiveBand, AudioEffectsController.MAX_STRENGTH)

        assertTrue(half[4] in 1 until full[4])
        assertEquals(maxLevel / 2, half[4])
    }

    // --- Bass ---

    @Test
    @DisplayName("bass lifts only the bands at or below 250 Hz")
    fun bassCoversTheBassRange() {
        val levels = bass(fiveBand, AudioEffectsController.MAX_STRENGTH)

        assertTrue(levels[0] > 0, "60 Hz must be lifted")
        assertTrue(levels[1] > 0, "230 Hz must be lifted")
        assertEquals(0, levels[2], "910 Hz is not bass")
        assertEquals(0, levels[3])
        assertEquals(0, levels[4])
    }

    @Test
    @DisplayName("bass rises towards the bottom of the spectrum")
    fun bassRamps() {
        val levels = bass(fiveBand, AudioEffectsController.MAX_STRENGTH)

        assertTrue(levels[0] > levels[1], "the shelf should rise towards the low end")
        assertEquals(maxLevel, levels[0], "the lowest band reaches the full control")
    }

    @Test
    @DisplayName("bass and treble never overlap")
    fun shelvesDoNotOverlap() {
        for (bands in listOf(fiveBand, tenBand)) {
            val trebleLevels = treble(bands, AudioEffectsController.MAX_STRENGTH)
            val bassLevels = bass(bands, AudioEffectsController.MAX_STRENGTH)

            bands.indices.forEach { index ->
                assertTrue(
                    trebleLevels[index] == 0 || bassLevels[index] == 0,
                    "band ${bands[index].centerFrequencyHz} Hz is in both shelves"
                )
            }
        }
    }

    // --- Boundaries ---

    @Test
    @DisplayName("zero strength changes nothing")
    fun zeroStrengthIsFlat() {
        assertTrue(treble(fiveBand, 0).all { it == 0 })
        assertTrue(bass(fiveBand, 0).all { it == 0 })
        assertTrue(treble(fiveBand, -5).all { it == 0 })
    }

    @Test
    @DisplayName("no bands means no levels, rather than a crash")
    fun noBands() {
        assertEquals(emptyList<Int>(), treble(emptyList(), AudioEffectsController.MAX_STRENGTH))
        assertEquals(emptyList<Int>(), bass(emptyList(), AudioEffectsController.MAX_STRENGTH))
    }

    @Test
    @DisplayName("a control never asks for more gain than the device allows")
    fun neverExceedsHeadroom() {
        for (strength in listOf(1, 250, 500, 999, AudioEffectsController.MAX_STRENGTH)) {
            assertTrue(treble(fiveBand, strength).all { it <= maxLevel })
            assertTrue(bass(fiveBand, strength).all { it <= maxLevel })
        }
    }

    @Test
    @DisplayName("a device with no headroom asks for no gain")
    fun noHeadroom() {
        val levels = AudioEffectsController.shelfLevels(
            fiveBand,
            maxLevelMillibel = 0,
            strength = AudioEffectsController.MAX_STRENGTH,
            high = true
        )
        assertTrue(levels.all { it == 0 })
    }

    @Test
    @DisplayName("a coarse equalizer with no band in the shelf still responds")
    fun coarseEqualizerFallsBackToTheEndBand() {
        // Three bands, none of which sits in the treble range. Doing nothing here
        // would be another silently dead control.
        val coarse = listOf(100, 500, 1_000).mapIndexed { index, hz ->
            AudioEffectsController.Band(index = index, centerFrequencyHz = hz)
        }

        val levels = treble(coarse, AudioEffectsController.MAX_STRENGTH)

        assertEquals(0, levels[0])
        assertEquals(0, levels[1])
        assertEquals(maxLevel, levels[2], "the highest band stands in for the shelf")
    }
}
