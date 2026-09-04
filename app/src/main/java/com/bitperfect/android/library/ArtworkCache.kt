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
        private const val TEMP_SUFFIX = ".tmp"
    }

    /**
     * Path of the cached image for a file, if one has already been written and
     * the source has not changed since.
     */
    fun find(sourceFile: File): String? {
        val target = entryFor(sourceFile)
        if (!target.isFile || target.length() <= 0) return null

        // Record the hit. [trim] evicts by modification time, and nothing else ever
        // updates it, so without this the cache evicts by write order and will drop
        // the cover of a constantly played album in favour of one never looked at.
        //
        // Safe to touch: the cache key is derived from the *source* file's size and
        // mtime, never from the entry's, so this cannot invalidate the entry.
        target.setLastModified(System.currentTimeMillis())

        return target.absolutePath
    }

    /**
     * Write artwork bytes for a source file and return the cached path.
     *
     * Writes to a temporary file and renames it into place, so a cancelled or
     * failed write can never leave a truncated image that later reads as valid.
     *
     * **Two callers really do ask for the same cover at the same moment**: a track
     * change makes the player resolve details for the UI while the playback
     * service resolves them for the notification. The temporary file used to be
     * named after the target, so those two writes shared one path — they
     * interleaved into a corrupt image, or one renamed the temporary away and the
     * other's rename then failed and reported no artwork at all. That is what made
     * covers appear in the app but not on the lock screen, or in neither, and only
     * sometimes. Each write now gets its own temporary, and the whole method is
     * serialised so the check-then-write below cannot be split.
     */
    @Synchronized
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

        // Unique per write. createTempFile also guarantees the name is not already
        // taken, which a name derived from the target cannot.
        val temporary = try {
            File.createTempFile(target.name, TEMP_SUFFIX, directory)
        } catch (error: Exception) {
            Log.w(TAG, "Could not create artwork temp file: ${error.message}")
            return null
        }

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
     *
     * Eviction leaves library rows pointing at files that no longer exist. That is
     * tolerated rather than tracked: `ArtworkResolver.isUsable` checks the file
     * before trusting the path, so the next play re-extracts the cover and rewrites
     * the row. The visible cost is a placeholder in the list until then.
     */
    private fun trim() {
        val entries = directory.listFiles()
            // Never count or evict a temporary: another write may be part way
            // through it, and deleting it would fail that write.
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && !it.name.endsWith(TEMP_SUFFIX) }
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
