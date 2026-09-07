package com.bitperfect.android.player

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The most recent audio, copied aside so a visualiser can look at it.
 *
 * Placed in Kotlin at the point both output paths read PCM, which is the only place
 * that sees all of it. The obvious alternative — tapping the C++ engine — was
 * rejected after tracing the data flow: on the Android output path FLAC, MP3, Opus,
 * AAC and OGG are decoded by `MediaCodecPcmSource`, whose samples never enter native
 * code at all. A native tap would therefore have drawn a dead spectrum for most
 * libraries, this project's own included, while appearing to work for WAV.
 *
 * **Nothing here is on the real-time path.** It is called from the sink worker
 * threads that already do file I/O and blocking writes, never from the USB reaper
 * thread that refills the isochronous queue. It reads the buffer and never writes to
 * it, so sample data cannot be altered: the spectrum is drawn from a copy.
 *
 * Lock-free by construction rather than by care: the writer fills the slab the reader
 * is not looking at and then publishes it with a single atomic store. No allocation
 * happens per chunk — both slabs are allocated once — so nothing here can trigger a
 * collection in the middle of playback.
 */
object SpectrumTap {

    /**
     * How many mono samples are kept.
     *
     * One analysis window. Older audio is of no interest: a spectrum shows what is
     * playing now.
     */
    private const val CAPACITY = SpectrumAnalysis.FFT_SIZE

    /**
     * Two slabs, alternating. The writer never touches the one it last published, so
     * a reader part-way through a copy cannot see a half-written window.
     */
    private val slabs = arrayOf(FloatArray(CAPACITY), FloatArray(CAPACITY))

    /** Scratch for the interleaved bytes, so the tap allocates nothing per chunk. */
    private var scratch = ByteArray(0)

    /** Index of the slab holding the most recently published window, or -1. */
    private val published = AtomicInteger(-1)

    private val active = AtomicBoolean(false)

    /** Sample rate of the published window, for turning bins into frequencies. */
    @Volatile
    var sampleRate: Int = 0
        private set

    /**
     * Whether anything is watching.
     *
     * Checked first in [submit] so the cost when no visualiser is on screen is a
     * single atomic read. A bit-perfect player should not spend cycles per chunk on a
     * picture nobody is looking at.
     */
    val isActive: Boolean get() = active.get()

    fun setActive(value: Boolean) {
        active.set(value)
        if (!value) published.set(-1)
    }

    /**
     * Take a copy of [buffer] for analysis.
     *
     * Read with absolute gets so the caller's position and limit are untouched — the
     * very next thing the caller does is hand this same buffer to the output.
     *
     * @param byteCount valid bytes from index 0.
     */
    fun submit(
        buffer: ByteBuffer,
        byteCount: Int,
        bitsPerSample: Int,
        channels: Int,
        sampleRate: Int
    ) {
        if (!active.get() || byteCount <= 0) return

        val bytesPerFrame = (bitsPerSample / 8) * channels
        if (bytesPerFrame <= 0) return

        // Only the tail is kept: a chunk can be several windows long, and the newest
        // audio is the only part worth showing.
        val wanted = minOf(byteCount, CAPACITY * bytesPerFrame)
        val start = byteCount - wanted

        if (scratch.size < wanted) scratch = ByteArray(wanted)
        for (i in 0 until wanted) scratch[i] = buffer.get(start + i)

        val target = if (published.get() == 0) 1 else 0
        val written = SpectrumAnalysis.decodeMono(
            source = scratch,
            byteCount = wanted,
            bitsPerSample = bitsPerSample,
            channels = channels,
            out = slabs[target]
        )
        if (written <= 0) return

        // Any tail left from a previous, longer chunk would otherwise be analysed as
        // if it were current audio.
        if (written < CAPACITY) slabs[target].fill(0f, written, CAPACITY)

        this.sampleRate = sampleRate
        // Single release: everything above is visible to a reader that sees this.
        published.set(target)
    }

    /**
     * Copy the latest window into [out].
     *
     * @return true if a window was available. False means nothing has been submitted
     *   since the tap was switched on — which the UI reports rather than drawing a
     *   flat line that would look like silence.
     */
    fun read(out: FloatArray): Boolean {
        val index = published.get()
        if (index < 0 || out.size < CAPACITY) return false
        slabs[index].copyInto(out, 0, 0, CAPACITY)
        return true
    }

    /** Forget the current window, so a stopped player does not keep drawing one. */
    fun clear() {
        published.set(-1)
    }
}
