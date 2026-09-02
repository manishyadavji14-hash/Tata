package com.bitperfect.android.player

import java.io.File
import java.security.MessageDigest

/**
 * Lyrics the user typed or pasted in, and lyrics the user asked to hide.
 *
 * Kept in the app's own storage rather than written next to the audio file.
 * Writing a `.lrc` beside a track on shared storage needs the MediaStore or SAF
 * consent dance on Android 11+, and would fail outright on a read-only volume or
 * an SD card. Neither is a good reason to refuse to let someone add lyrics, so
 * these live where the app can always write.
 *
 * Two states are recorded, and the difference matters:
 *
 * - **an override**: text supplied by the user, which wins over the file's own
 *   sidecar and tags.
 * - **suppressed**: the user removed lyrics for this track. Without recording
 *   that, "remove lyrics" on a file with an embedded `USLT` frame would appear to
 *   work and then have the lyrics reappear on the next play, because they are
 *   still in the file and the app would read them again.
 *
 * Files are named by a hash of the audio path: paths contain separators, are far
 * longer than most filesystems allow, and are not case-stable. Nothing reads the
 * names, so a hash costs nothing.
 *
 * Pure `java.io` and no Android APIs, so the precedence rules are unit-testable.
 */
class LyricsOverrideStore(private val directory: File) {

    /** User-supplied lyrics for [audioPath], or null when there are none. */
    fun read(audioPath: String): String? {
        val file = overrideFile(audioPath) ?: return null
        return try {
            if (!file.isFile || file.length() !in 1..MAX_BYTES) return null
            file.readText().takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            // Unreadable override is the same as no override; never a crash on a
            // track change.
            null
        }
    }

    /** Whether the user removed lyrics for this track. */
    fun isSuppressed(audioPath: String): Boolean =
        try {
            suppressionFile(audioPath)?.isFile == true
        } catch (error: Exception) {
            false
        }

    /**
     * Store lyrics for [audioPath], clearing any suppression.
     *
     * @return true when it was written.
     */
    fun save(audioPath: String, lyrics: String): Boolean {
        if (lyrics.isBlank()) return false
        val file = overrideFile(audioPath) ?: return false
        return try {
            directory.mkdirs()
            file.writeText(lyrics)
            suppressionFile(audioPath)?.delete()
            true
        } catch (error: Exception) {
            false
        }
    }

    /**
     * Record that this track should show no lyrics, discarding any override.
     *
     * @return true when the marker was written.
     */
    fun suppress(audioPath: String): Boolean {
        val marker = suppressionFile(audioPath) ?: return false
        return try {
            directory.mkdirs()
            overrideFile(audioPath)?.delete()
            marker.writeText("")
            true
        } catch (error: Exception) {
            false
        }
    }

    /** Forget both the override and the suppression, restoring the file's own. */
    fun clear(audioPath: String) {
        try {
            overrideFile(audioPath)?.delete()
            suppressionFile(audioPath)?.delete()
        } catch (error: Exception) {
            // Nothing useful to do: the next read simply falls back to the file.
        }
    }

    private fun overrideFile(audioPath: String): File? =
        key(audioPath)?.let { File(directory, "$it.lrc") }

    private fun suppressionFile(audioPath: String): File? =
        key(audioPath)?.let { File(directory, "$it.none") }

    private fun key(audioPath: String): String? {
        if (audioPath.isBlank()) return null
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(audioPath.toByteArray())
                .take(KEY_BYTES)
                .joinToString("") { "%02x".format(it) }
        } catch (error: Exception) {
            null
        }
    }

    private companion object {
        /**
         * 16 bytes of SHA-256. Collisions are not a practical concern for the
         * number of files on a phone, and the names stay short.
         */
        const val KEY_BYTES = 16

        /** Matches the sidecar cap in LyricsRepository; lyrics are kilobytes. */
        const val MAX_BYTES = 512L * 1024L
    }
}
