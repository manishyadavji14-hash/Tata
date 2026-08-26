package com.bitperfect.android.library

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * On-disk cache for artwork extracted from audio files.
 *
 * Only files the media index does not cover need this. Indexed tracks carry a
 * MediaStore album-art content URI that Coil loads directly, and nothing is
 * written for them.
 *
 * The cache key is a digest of the path together with the file's size and
 * modification time. Keying on the path alone means a re-tagged file keeps its
 * old cover forever, and a 32-bit string hash lets two paths collide onto one
 * image. Including size and mtime makes the entry self-invalidating: edit the
 * file and it simply misses.
 */
class ArtworkCache(
    private val directory: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {

    companion object {
        private const val TAG = "ArtworkCache"
        private const val DEFAULT_MAX_ENTRIES = 512
        private const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
        private const val FILE_PREFIX = "art_"
        private const val FILE_SUFFIX = ".img"
    }

    /**
     * Path of the cached image for a file, if one has already been written and
     * the source has not changed since.
     */
    fun find(sourceFile: File): String? {
        val target = entryFor(sourceFile)
        return if (target.isFile && target.length() > 0) target.absolutePath else null
    }

    /**
     * Write artwork bytes for a source file and return the cached path.
     *
     * Writes to a temporary file and renames it into place, so a cancelled or
     * failed write can never leave a truncated image that later reads as valid.
     */
    fun put(sourceFile: File, imageBytes: ByteArray): String? {
        if (imageBytes.isEmpty()) return null
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create artwork cache directory")
            return null
        }

        val target = entryFor(sourceFile)
        if (target.isFile && target.length() == imageBytes.size.toLong()) {
            return target.absolutePath
        }

        val temporary = File(directory, "${target.name}.tmp")
        return try {
            temporary.outputStream().use { it.write(imageBytes) }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return null
            }
            trim()
            target.absolutePath
        } catch (error: Exception) {
            Log.w(TAG, "Could not cache artwork: ${error.message}")
            temporary.delete()
            null
        }
    }

    /**
     * Drop every cached image.
     */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    /**
     * Evict least-recently-used entries until the cache is inside its limits.
     */
    private fun trim() {
        val entries = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) }
            ?: return

        var totalBytes = entries.sumOf { it.length() }
        if (entries.size <= maxEntries && totalBytes <= maxBytes) return

        // Oldest access first; lastModified is the only timestamp available.
        val oldestFirst = entries.sortedBy { it.lastModified() }
        var remaining = entries.size

        for (entry in oldestFirst) {
            if (remaining <= maxEntries && totalBytes <= maxBytes) break
            val size = entry.length()
            if (entry.delete()) {
                remaining--
                totalBytes -= size
            }
        }
    }

    /**
     * Cache file for a source, derived from its identity and current state.
     */
    private fun entryFor(sourceFile: File): File {
        val key = digest(
            "${sourceFile.absolutePath}:${sourceFile.length()}:${sourceFile.lastModified()}"
        )
        return File(directory, "$FILE_PREFIX$key$FILE_SUFFIX")
    }

    private fun digest(value: String): String {
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .take(16)
                .joinToString("") { "%02x".format(it) }
        } catch (error: Exception) {
            // Every Android runtime ships SHA-256; this is belt and braces.
            value.hashCode().toUInt().toString(16)
        }
    }
}
