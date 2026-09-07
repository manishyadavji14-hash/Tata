package com.bitperfect.android.player

import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The spectrum's arithmetic.
 *
 * There is no device here, so "it looks about right" is not available — but none of
 * this needs a device to be checked. A tone of a known frequency has to land in the
 * bin that frequency belongs to; silence has to read as silence; and a 24-bit sample
 * has to come out at the same amplitude as the 16-bit one representing the same
 * level. Those are facts, and they are the ones that decide whether the display means
 * anything at all.
 */
@DisplayName("SpectrumAnalysis Tests")
class SpectrumAnalysisTest {

    private val fftSize = SpectrumAnalysis.FFT_SIZE

    /** Interleaved little-endian PCM from whole samples. */
    private fun pcm(bitsPerSample: Int, vararg samples: Int): ByteArray {
        val width = bitsPerSample / 8
        val out = ByteArray(samples.size * width)
        samples.forEachIndexed { index, value ->
            for (byte in 0 until width) {
                out[index * width + byte] = ((value shr (8 * byte)) and 0xFF).toByte()
            }
        }
        return out
    }

    // --- Decoding PCM ---

    @Test
    @DisplayName("16-bit full scale reaches the ends of the range")
    fun sixteenBitFullScale() {
        val out = FloatArray(2)

        SpectrumAnalysis.decodeMono(pcm(16, 32767, -32768), 4, 16, 1, out)

        assertEquals(1f, out[0], 0.001f)
        assertEquals(-1f, out[1], 0.001f)
    }

    @Test
    @DisplayName("24-bit packed samples are sign-extended, not read as positive")
    fun twentyFourBitSignExtension() {
        // The one that is easy to get wrong: three bytes is not a 32-bit value with a
        // spare byte, and without extending the sign every negative sample reads as a
        // large positive one — which shows up as a spectrum that never goes quiet.
        val out = FloatArray(2)

        SpectrumAnalysis.decodeMono(pcm(24, 8_388_607, -8_388_608), 6, 24, 1, out)

        assertEquals(1f, out[0], 0.001f)
        assertEquals(-1f, out[1], 0.001f)
    }

    @Test
    @DisplayName("the same level reads the same at every bit depth")
    fun depthsAgreeOnAmplitude() {
        // Half scale in each width. If the divisor were wrong for one of them, that
        // format's spectrum would be permanently louder or quieter than the others.
        val sixteen = FloatArray(1)
        val twentyFour = FloatArray(1)
        val thirtyTwo = FloatArray(1)

        SpectrumAnalysis.decodeMono(pcm(16, 16_384), 2, 16, 1, sixteen)
        SpectrumAnalysis.decodeMono(pcm(24, 4_194_304), 3, 24, 1, twentyFour)
        SpectrumAnalysis.decodeMono(pcm(32, 1_073_741_824), 4, 32, 1, thirtyTwo)

        assertEquals(0.5f, sixteen[0], 0.001f)
        assertEquals(0.5f, twentyFour[0], 0.001f)
        assertEquals(0.5f, thirtyTwo[0], 0.001f)
    }

    @Test
    @DisplayName("stereo is averaged, so a hard-panned mix is not half quiet")
    fun stereoDownmix() {
        val out = FloatArray(2)

        // Frame 1: full left, silent right. Frame 2: both full.
        SpectrumAnalysis.decodeMono(pcm(16, 32767, 0, 32767, 32767), 8, 16, 2, out)

        assertEquals(0.5f, out[0], 0.001f)
        assertEquals(1f, out[1], 0.001f)
    }

    @Test
    @DisplayName("a partial frame at the end is dropped rather than misread")
    fun partialFrameIgnored() {
        val out = FloatArray(4)

        // Five bytes of 16-bit stereo: one whole frame plus a stray byte.
        val written = SpectrumAnalysis.decodeMono(pcm(16, 100, 100, 100), 5, 16, 2, out)

        assertEquals(1, written, "a half-read frame would shift every later sample")
    }

    @Test
    @DisplayName("nonsense input yields nothing rather than throwing")
    fun refusesNonsense() {
        val out = FloatArray(8)

        assertEquals(0, SpectrumAnalysis.decodeMono(ByteArray(8), 0, 16, 2, out))
        assertEquals(0, SpectrumAnalysis.decodeMono(ByteArray(8), 8, 8, 2, out))
        assertEquals(0, SpectrumAnalysis.decodeMono(ByteArray(8), 8, 16, 0, out))
        assertEquals(0, SpectrumAnalysis.decodeMono(ByteArray(8), 8, 16, 2, FloatArray(0)))
    }

    @Test
    @DisplayName("output is bounded by the destination, not the input")
    fun neverOverrunsOutput() {
        val out = FloatArray(2)

        val written = SpectrumAnalysis.decodeMono(ByteArray(400), 400, 16, 1, out)

        assertEquals(2, written)
    }

    // --- Window ---

    @Test
    @DisplayName("the window closes at both ends and opens in the middle")
    fun hannShape() {
        val window = SpectrumAnalysis.hannWindow(64)

        assertEquals(0f, window.first(), 0.001f)
        assertEquals(0f, window.last(), 0.001f)
        assertEquals(1f, window[32], 0.01f)
        // Symmetric, or the window itself would shift the phase of everything.
        for (i in window.indices) {
            assertEquals(window[i], window[window.size - 1 - i], 0.001f, "asymmetric at $i")
        }
    }

    // --- The transform, which is the claim worth proving ---

    @Test
    @DisplayName("a 1 kHz tone at 44.1 kHz peaks in the bin 1 kHz belongs to")
    fun toneLandsInTheRightBin() {
        // This is the test that says the transform is a transform. 44100/1024 gives
        // 43.07 Hz per bin, so 1000 Hz belongs to bin 23.
        val sampleRate = 44_100
        val toneHz = 1_000f
        val expectedBin = (toneHz / (sampleRate.toFloat() / fftSize)).toInt()

        val samples = FloatArray(fftSize) { i ->
            sin(2.0 * PI * toneHz * i / sampleRate).toFloat()
        }
        val hann = SpectrumAnalysis.hannWindow(fftSize)
        for (i in samples.indices) samples[i] *= hann[i]

        val magnitudes = FloatArray(fftSize / 2)
        SpectrumAnalysis.magnitudes(samples, magnitudes)

        val loudest = magnitudes.indices.maxBy { magnitudes[it] }
        assertTrue(
            kotlin.math.abs(loudest - expectedBin) <= 1,
            "1 kHz landed in bin $loudest, expected about $expectedBin"
        )
    }

    @Test
    @DisplayName("a higher tone lands higher up, and a lower one lower down")
    fun binScalesWithFrequency() {
        val sampleRate = 48_000

        fun peakBinOf(hz: Float): Int {
            val samples = FloatArray(fftSize) { i ->
                sin(2.0 * PI * hz * i / sampleRate).toFloat()
            }
            val hann = SpectrumAnalysis.hannWindow(fftSize)
            for (i in samples.indices) samples[i] *= hann[i]
            val magnitudes = FloatArray(fftSize / 2)
            SpectrumAnalysis.magnitudes(samples, magnitudes)
            return magnitudes.indices.maxBy { magnitudes[it] }
        }

        val low = peakBinOf(500f)
        val mid = peakBinOf(2_000f)
        val high = peakBinOf(8_000f)

        assertTrue(low < mid && mid < high, "bins were $low, $mid, $high")
    }

    @Test
    @DisplayName("silence produces no magnitude anywhere")
    fun silenceIsSilent() {
        val magnitudes = FloatArray(fftSize / 2) { 1f }

        SpectrumAnalysis.magnitudes(FloatArray(fftSize), magnitudes)

        assertTrue(magnitudes.all { it < 0.0001f }, "silence produced a magnitude")
    }

    @Test
    @DisplayName("a constant signal is all at DC")
    fun constantIsDc() {
        val magnitudes = FloatArray(fftSize / 2)

        SpectrumAnalysis.magnitudes(FloatArray(fftSize) { 0.5f }, magnitudes)

        assertEquals(0, magnitudes.indices.maxBy { magnitudes[it] })
    }

    @Test
    @DisplayName("a size that is not a power of two is refused, not silently wrong")
    fun refusesOddSizes() {
        assertThrows<IllegalArgumentException> {
            SpectrumAnalysis.magnitudes(FloatArray(1000), FloatArray(500))
        }
    }

    // --- Bands ---

    @Test
    @DisplayName("bands rise, never repeat, and stay inside the spectrum")
    fun bandEdgesAreUsable() {
        for (rate in listOf(44_100, 48_000, 96_000, 192_000)) {
            val edges = SpectrumAnalysis.bandEdges(rate, fftSize, SpectrumAnalysis.BAND_COUNT)

            assertEquals(SpectrumAnalysis.BAND_COUNT + 1, edges.size, "rate=$rate")
            for (i in 1 until edges.size) {
                assertTrue(
                    edges[i] > edges[i - 1],
                    "rate=$rate band $i is empty: ${edges.toList()}"
                )
            }
            assertTrue(edges.first() >= 1, "rate=$rate started at DC")
            assertTrue(edges.last() <= fftSize / 2, "rate=$rate ran past the spectrum")
        }
    }

    @Test
    @DisplayName("bands are logarithmic, so the low end is not squeezed out")
    fun bandsAreLogarithmic() {
        val edges = SpectrumAnalysis.bandEdges(44_100, fftSize, SpectrumAnalysis.BAND_COUNT)

        val firstWidth = edges[1] - edges[0]
        val lastWidth = edges[edges.size - 1] - edges[edges.size - 2]

        // A *large* ratio, not merely "greater". Linear spacing over this range gives
        // widths of about 12 and 13 bins — which satisfies "the last is wider than the
        // first" purely through rounding, and a mutation to linear spacing survived
        // that weaker assertion. Logarithmic spacing gives roughly 1 and 65.
        assertTrue(
            lastWidth > firstWidth * 4,
            "bands look linear (first=$firstWidth, last=$lastWidth); the top octaves " +
                "would take most of the display and the bass would be a single bar"
        )
    }

    @Test
    @DisplayName("a nonsense rate yields no bands rather than a crash")
    fun bandEdgesRefuseNonsense() {
        assertEquals(0, SpectrumAnalysis.bandEdges(0, fftSize, 8).size)
        assertEquals(0, SpectrumAnalysis.bandEdges(44_100, fftSize, 0).size)
    }

    // --- Levels ---

    @Test
    @DisplayName("nothing is at the floor, full scale is at the top")
    fun levelRange() {
        assertEquals(0f, SpectrumAnalysis.normaliseDb(0f))
        assertEquals(1f, SpectrumAnalysis.normaliseDb(fftSize.toFloat()), 0.05f)
    }

    @Test
    @DisplayName("level rises with magnitude and is always drawable")
    fun levelIsMonotonicAndBounded() {
        var previous = -1f
        for (magnitude in listOf(0f, 0.01f, 0.5f, 4f, 32f, 256f, 4096f)) {
            val level = SpectrumAnalysis.normaliseDb(magnitude)
            assertTrue(level in 0f..1f, "magnitude $magnitude gave $level")
            assertTrue(level >= previous, "level fell as magnitude rose, at $magnitude")
            previous = level
        }
    }

    @Test
    @DisplayName("equal power per octave reads as equal height, so the display is balanced")
    fun pinkNoiseIsFlat() {
        // The property that makes a music spectrum meaningful. Pink noise carries equal
        // power in every octave and is roughly what music looks like, so it is the
        // reference the display should be flat against.
        //
        // This is what taking each band's loudest bin got wrong: that measures the
        // spectrum at the band's lower edge, which for pink noise slopes down about
        // 3 dB per octave — so the treble end of the display was permanently
        // understated. Summing power makes it flat.
        val magnitudes = FloatArray(fftSize / 2) { bin ->
            // power ∝ 1/f, so magnitude ∝ 1/sqrt(f)
            if (bin == 0) 0f else (400f / kotlin.math.sqrt(bin.toFloat()))
        }
        val edges = SpectrumAnalysis.bandEdges(44_100, fftSize, SpectrumAnalysis.BAND_COUNT)

        val levels = (0 until SpectrumAnalysis.BAND_COUNT).map { band ->
            SpectrumAnalysis.bandLevel(magnitudes, edges[band], edges[band + 1])
        }

        // Ignore the first few bands: below about 100 Hz a band is a single bin, so it
        // cannot hold an octave's worth of anything and the ideal does not apply.
        val comparable = levels.drop(6)
        val spread = (comparable.max() - comparable.min())

        assertTrue(
            spread < 0.12f,
            "pink noise came out sloped by ${"%.3f".format(spread)} of the display " +
                "height; levels were ${comparable.map { "%.2f".format(it) }}"
        )
    }

    @Test
    @DisplayName("equal power per hertz rises, because white noise really is treble-heavy")
    fun whiteNoiseRises() {
        // The same mechanism seen from the other side: white noise has equal power per
        // hertz, so a logarithmic band covers more of it as frequency climbs. A display
        // that did not rise here would be flattening something real.
        val magnitudes = FloatArray(fftSize / 2) { if (it == 0) 0f else 4f }
        val edges = SpectrumAnalysis.bandEdges(44_100, fftSize, SpectrumAnalysis.BAND_COUNT)

        val first = SpectrumAnalysis.bandLevel(magnitudes, edges[8], edges[9])
        val last = SpectrumAnalysis.bandLevel(
            magnitudes,
            edges[SpectrumAnalysis.BAND_COUNT - 1],
            edges[SpectrumAnalysis.BAND_COUNT]
        )

        assertTrue(last > first, "white noise read as $first then $last")
    }

    @Test
    @DisplayName("a band reports the energy it contains, whatever its width")
    fun bandTakesThePeak() {
        // Averaging buries a narrow peak, and a musical note is a narrow peak.
        val magnitudes = FloatArray(64)
        magnitudes[10] = 500f

        // The test that actually distinguishes the two: one loud bin must read the
        // same whether its band is one bin wide or thirty. Taking the peak gives the
        // same answer; averaging divides it by the band width, so a wide band reads
        // far quieter. Asserting only "the peak is audible" was not enough — a mean
        // over eight bins is still loud enough to pass that, and the mutation
        // survived it.
        val narrow = SpectrumAnalysis.bandLevel(magnitudes, 10, 11)
        val wide = SpectrumAnalysis.bandLevel(magnitudes, 4, 34)

        assertEquals(narrow, wide, 0.0001f, "a wide band diluted the peak instead of reporting it")
        assertTrue(narrow > 0.5f, "the peak was lost: $narrow")
        assertEquals(0f, SpectrumAnalysis.bandLevel(magnitudes, 20, 28))
    }

    @Test
    @DisplayName("an empty or out-of-range band is zero, not an exception")
    fun bandLevelBounds() {
        val magnitudes = FloatArray(16) { 100f }

        assertEquals(0f, SpectrumAnalysis.bandLevel(magnitudes, 8, 8))
        assertEquals(0f, SpectrumAnalysis.bandLevel(magnitudes, 8, 4))
        assertEquals(0f, SpectrumAnalysis.bandLevel(magnitudes, -1, 4))
        assertEquals(0f, SpectrumAnalysis.bandLevel(magnitudes, 8, 999))
    }

    // --- Decay ---

    @Test
    @DisplayName("bars jump up instantly and fall slowly")
    fun decayShape() {
        // Following the true value downwards as fast as upwards produces a flicker
        // nobody can read.
        assertEquals(0.9f, SpectrumAnalysis.decayed(previous = 0.2f, next = 0.9f, decay = 0.8f))

        val falling = SpectrumAnalysis.decayed(previous = 1f, next = 0f, decay = 0.8f)
        assertEquals(0.8f, falling, 0.001f)
    }

    @Test
    @DisplayName("a falling bar reaches the floor rather than hovering")
    fun decayTerminates() {
        var level = 1f
        repeat(200) { level = SpectrumAnalysis.decayed(level, 0f, 0.8f) }

        assertTrue(level < 0.001f, "still at $level after 200 frames")
    }

    @Test
    @DisplayName("decay never takes a bar below the level actually present")
    fun decayRespectsCurrentLevel() {
        // Otherwise a sustained note would visibly sag while still sounding.
        assertEquals(0.5f, SpectrumAnalysis.decayed(previous = 0.5f, next = 0.5f, decay = 0.5f))
        assertEquals(0.4f, SpectrumAnalysis.decayed(previous = 0.5f, next = 0.4f, decay = 0.1f))
    }
}
