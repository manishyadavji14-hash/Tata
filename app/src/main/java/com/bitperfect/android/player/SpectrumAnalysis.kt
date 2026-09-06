package com.bitperfect.android.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turning PCM into something a spectrum can be drawn from.
 *
 * Every step is a pure function, because this is arithmetic that is either right or
 * wrong and there is no device here to look at. A sine wave has to land in a known
 * bin; silence has to read as silence; 24-bit packed samples have to come out at the
 * same amplitude as 16-bit ones. All of that is checked in `SpectrumAnalysisTest`.
 *
 * Deliberately **not** `android.media.audiofx.Visualizer`, which would have been the
 * obvious route:
 *
 * - it needs `RECORD_AUDIO`, and a microphone prompt in a music player is
 *   indefensible;
 * - it attaches to an audio session, and the bit-perfect USB path has none — so it
 *   would have been dead precisely on the output this app exists for;
 * - it is documented as returning partial, low-quality audio.
 *
 * Reading the PCM this app has already decoded needs no permission, works on both
 * output paths, and is exact.
 */
object SpectrumAnalysis {

    /**
     * Samples per analysis window.
     *
     * A power of two for the radix-2 transform. 1024 at 44.1 kHz is a 23 ms window:
     * long enough to resolve about 43 Hz per bin, short enough that the display still
     * tracks a beat rather than smearing it.
     */
    const val FFT_SIZE = 1024

    /** Bars drawn. Enough to read as a spectrum, few enough to be legible on a phone. */
    const val BAND_COUNT = 28

    /** Bottom of the displayed range. Below this everything reads as silence. */
    const val FLOOR_DB = -66f

    /**
     * Lowest and highest frequency given a band.
     *
     * Not the full 20 Hz–22 kHz: the bottom two octaves of a phone's output carry
     * almost nothing, and the top octave is mostly inaudible, so spending bars there
     * makes the interesting middle narrower for no gain.
     */
    const val MIN_HZ = 45f
    const val MAX_HZ = 15_000f

    // --- PCM to mono float ---

    /**
     * Decode interleaved integer PCM into mono samples in `-1..1`.
     *
     * Handles the three widths the decoders emit, including **24-bit packed into
     * three bytes**, which is the one that is easy to get wrong: it is not a 32-bit
     * value with a spare byte, and sign-extending it by hand is the only way to read
     * it. Channels are averaged rather than taking the left one, so a hard-panned
     * mix does not read as half amplitude on one side and nothing on the other.
     *
     * @return the number of mono samples written to [out].
     */
    fun decodeMono(
        source: ByteArray,
        byteCount: Int,
        bitsPerSample: Int,
        channels: Int,
        out: FloatArray
    ): Int {
        if (byteCount <= 0 || channels <= 0 || out.isEmpty()) return 0

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 2..4) return 0

        val bytesPerFrame = bytesPerSample * channels
        if (bytesPerFrame <= 0) return 0

        val frames = min(byteCount / bytesPerFrame, out.size)
        // Full scale for a signed sample of this width.
        val scale = 1f / (1L shl (bitsPerSample - 1)).toFloat()

        var index = 0
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += readSample(source, index, bytesPerSample) * scale
                index += bytesPerSample
            }
            out[frame] = sum / channels
        }
        return frames
    }

    /** One little-endian signed sample of [bytesPerSample] bytes. */
    private fun readSample(source: ByteArray, offset: Int, bytesPerSample: Int): Float =
        when (bytesPerSample) {
            2 -> {
                val value = (source[offset].toInt() and 0xFF) or
                    (source[offset + 1].toInt() shl 8)
                value.toFloat()
            }

            3 -> {
                // Sign-extended by hand: the top byte carries the sign, and shifting
                // it into position is what makes a negative sample negative.
                val value = (source[offset].toInt() and 0xFF) or
                    ((source[offset + 1].toInt() and 0xFF) shl 8) or
                    (source[offset + 2].toInt() shl 16)
                value.toFloat()
            }

            else -> {
                val value = (source[offset].toInt() and 0xFF) or
                    ((source[offset + 1].toInt() and 0xFF) shl 8) or
                    ((source[offset + 2].toInt() and 0xFF) shl 16) or
                    (source[offset + 3].toInt() shl 24)
                value.toFloat()
            }
        }

    // --- Window ---

    /**
     * Hann window of [size].
     *
     * Without a window, the abrupt ends of each block behave like a step change and
     * smear energy across every bin — a pure tone comes out as a wall rather than a
     * peak.
     */
    fun hannWindow(size: Int): FloatArray {
        if (size <= 1) return FloatArray(max(size, 0)) { 1f }
        return FloatArray(size) { i ->
            (0.5f - 0.5f * cos(2.0 * PI * i / (size - 1)).toFloat())
        }
    }

    // --- Transform ---

    /**
     * Magnitude spectrum of [samples], in place-free form.
     *
     * An iterative radix-2 Cooley-Tukey transform on a real signal: the imaginary
     * part starts at zero and the second half of the output is the mirror of the
     * first, so only `size / 2` magnitudes are meaningful and only those are
     * returned.
     *
     * @param samples exactly [FFT_SIZE] windowed samples.
     * @param magnitudes output of at least `size / 2`.
     */
    fun magnitudes(samples: FloatArray, magnitudes: FloatArray) {
        val size = samples.size
        require(size > 0 && size and (size - 1) == 0) { "FFT size must be a power of two" }

        val real = samples.copyOf()
        val imaginary = FloatArray(size)

        // Bit-reversal permutation, so the butterflies below can run in place.
        var target = 0
        for (i in 0 until size) {
            if (target > i) {
                val swap = real[i]
                real[i] = real[target]
                real[target] = swap
            }
            var mask = size shr 1
            while (mask != 0 && target and mask != 0) {
                target = target xor mask
                mask = mask shr 1
            }
            target = target or mask
        }

        var span = 1
        while (span < size) {
            val step = span shl 1
            val angleStep = -PI / span
            for (group in 0 until span) {
                val angle = angleStep * group
                val cosine = cos(angle).toFloat()
                val sine = kotlin.math.sin(angle).toFloat()
                var i = group
                while (i < size) {
                    val pair = i + span
                    val realPart = real[pair] * cosine - imaginary[pair] * sine
                    val imaginaryPart = real[pair] * sine + imaginary[pair] * cosine
                    real[pair] = real[i] - realPart
                    imaginary[pair] = imaginary[i] - imaginaryPart
                    real[i] += realPart
                    imaginary[i] += imaginaryPart
                    i += step
                }
            }
            span = step
        }

        val half = size / 2
        for (bin in 0 until half) {
            magnitudes[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
        }
    }

    // --- Bands ---

    /**
     * Bin index at which each band starts, plus a final end, so band `i` covers
     * `edges[i] until edges[i + 1]`.
     *
     * Logarithmically spaced, because pitch is: linear bands would give three
     * quarters of the display to the top two octaves, where there is least to see.
     * Each edge is forced at least one bin past the last, so no band is empty and
     * every bar has something to show.
     */
    fun bandEdges(sampleRate: Int, fftSize: Int, bandCount: Int): IntArray {
        val half = fftSize / 2
        if (sampleRate <= 0 || half <= 0 || bandCount <= 0) return IntArray(0)

        val hzPerBin = sampleRate.toFloat() / fftSize
        val lowest = MIN_HZ / hzPerBin
        val highest = min(MAX_HZ / hzPerBin, (half - 1).toFloat())

        val edges = IntArray(bandCount + 1)
        var previous = max(1, lowest.toInt())
        for (band in 0..bandCount) {
            val fraction = band.toFloat() / bandCount
            // Geometric interpolation between the two ends.
            val bin = lowest * Math.pow((highest / lowest).toDouble(), fraction.toDouble()).toFloat()
            edges[band] = min(max(bin.toInt(), previous), half)
            if (band > 0 && edges[band] <= edges[band - 1]) {
                edges[band] = min(edges[band - 1] + 1, half)
            }
            previous = edges[band]
        }
        return edges
    }

    /**
     * Level of one band, as `0..1` from [FLOOR_DB] to full scale.
     *
     * The loudest bin in the band rather than the mean: averaging across a band that
     * spans several bins buries a narrow peak, and a narrow peak is exactly what a
     * musical note is.
     */
    fun bandLevel(magnitudes: FloatArray, fromBin: Int, toBin: Int): Float {
        if (fromBin >= toBin || fromBin < 0 || toBin > magnitudes.size) return 0f

        var peak = 0f
        for (bin in fromBin until toBin) peak = max(peak, magnitudes[bin])

        return normaliseDb(peak)
    }

    /**
     * A magnitude as `0..1` over [FLOOR_DB].
     *
     * Decibels rather than raw magnitude, because hearing is logarithmic: on a linear
     * scale everything but the loudest peak sits invisibly close to the floor.
     */
    fun normaliseDb(magnitude: Float): Float {
        if (magnitude <= 0f) return 0f
        // Referenced to the window's own scale so a full-scale tone reaches the top.
        val db = 20f * log10(magnitude / (FFT_SIZE / 4f))
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }

    /**
     * Bar height after decay.
     *
     * Bars rise instantly to a new peak and fall slowly, which is what makes a
     * spectrum readable: following the true value in both directions produces a
     * flicker nobody can track.
     */
    fun decayed(previous: Float, next: Float, decay: Float): Float =
        if (next >= previous) next else max(next, previous * decay)
}
