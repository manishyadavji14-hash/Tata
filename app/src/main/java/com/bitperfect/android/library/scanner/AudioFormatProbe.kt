package com.bitperfect.android.library.scanner

/**
 * Reads exact PCM format details straight from a file.
 *
 * MediaStore only reports sample rate and bit depth from Android 12 (API 31).
 * Below that a scan would leave both at zero, which makes every track look
 * like standard resolution and empties the format text in the library.
 *
 * Implementations open the file, so probing is comparatively expensive and is
 * only used where the media index came back empty.
 */
fun interface AudioFormatProbe {

    /**
     * @return The file's format, or null if it cannot be determined.
     */
    fun probe(path: String): ProbedFormat?
}

/**
 * Exact format as reported by a decoder.
 */
data class ProbedFormat(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    /**
     * Length as the decoder computes it, or 0 when unknown.
     *
     * Carried because duration had no measured source at all: the scan took it from
     * the media index and nowhere else, so a stale index meant a stale duration even
     * once the rate and bit depth had been corrected by a probe. Duration is also the
     * figure the player compares against to notice the library is out of date, so it
     * being uncorrectable made the mismatch permanent.
     */
    val durationMs: Long = 0L
) {
    val isUsable: Boolean
        get() = sampleRate > 0 && bitDepth > 0
}
