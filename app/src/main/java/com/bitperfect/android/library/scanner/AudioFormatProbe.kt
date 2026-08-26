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
    val channels: Int
) {
    val isUsable: Boolean
        get() = sampleRate > 0 && bitDepth > 0
}
