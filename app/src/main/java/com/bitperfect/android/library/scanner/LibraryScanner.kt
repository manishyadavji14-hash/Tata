package com.bitperfect.android.library.scanner

import android.content.Context
import com.bitperfect.android.library.MetadataExtractor
import com.bitperfect.android.library.model.Track

/**
 * LibraryScanner - discovers audio files on the device and turns them into
 * library tracks.
 *
 * Discovery goes through MediaStore rather than a filesystem walk: scoped
 * storage makes direct traversal unreliable from Android 11 onward, and the
 * media index already carries the tags needed, so a scan does not have to
 * open every file. MediaStore still yields an absolute path, which the native
 * decoders require.
 *
 * Files MediaStore has not indexed can still be added individually through
 * scanSingleFile(), which reads tags with MediaMetadataRetriever.
 */
class LibraryScanner(
    private val context: Context,
    private val metadataExtractor: MetadataExtractor = MetadataExtractor(),
    private val audioSource: MediaStoreAudioSource = MediaStoreAudioSource(context),
    /**
     * Fills in sample rate and bit depth that MediaStore cannot report below
     * Android 12. Optional: without it those fields stay zero.
     */
    private val formatProbe: AudioFormatProbe? = null
) {

    /**
     * Scan state.
     */
    enum class ScanState {
        IDLE,
        SCANNING,
        PROCESSING,
        COMPLETED,
        CANCELLED,
        ERROR
    }

    /**
     * Scan progress information.
     */
    data class ScanProgress(
        val state: ScanState = ScanState.IDLE,
        val filesFound: Int = 0,
        val filesProcessed: Int = 0,
        val currentFile: String = "",
        val tracksAdded: Int = 0,
        val tracksUpdated: Int = 0,
        val tracksRemoved: Int = 0
    ) {
        val progressPercent: Float
            get() = if (filesFound > 0) filesProcessed.toFloat() / filesFound else 0f
    }

    /**
     * Scan result.
     *
     * @param tracks Tracks discovered by this scan, ready to be persisted.
     * @param removedPaths Previously known paths that no longer exist.
     */
    data class ScanResult(
        val success: Boolean,
        val tracksAdded: Int = 0,
        val tracksUpdated: Int = 0,
        val tracksRemoved: Int = 0,
        val totalTracks: Int = 0,
        val durationMs: Long = 0,
        val error: String? = null,
        val tracks: List<Track> = emptyList(),
        val removedPaths: List<String> = emptyList()
    )

    @Volatile
    private var currentState: ScanState = ScanState.IDLE

    @Volatile
    private var isCancelled: Boolean = false

    private var progressCallback: ((ScanProgress) -> Unit)? = null

    /**
     * Set the progress callback.
     */
    fun setProgressCallback(callback: (ScanProgress) -> Unit) {
        progressCallback = callback
    }

    /**
     * Scan for audio files.
     *
     * @param directories Absolute directory paths to restrict the scan to.
     *   Empty scans every indexed audio file on the device.
     * @param existingTracks Tracks already in the library, keyed by path, used
     *   to detect modifications and removals.
     * @return Scan result including the tracks to persist.
     */
    fun scan(
        directories: List<String> = emptyList(),
        existingTracks: Map<String, Track> = emptyMap(),
        /**
         * When set, only files with these (lowercase, no-dot) extensions are
         * taken. A format-restricted scan is additive: it never removes tracks
         * of other formats, so "scan only FLAC" cannot wipe your MP3s.
         */
        formatFilter: Set<String>? = null
    ): ScanResult {
        val startTime = System.currentTimeMillis()
        currentState = ScanState.SCANNING
        isCancelled = false

        progressCallback?.invoke(ScanProgress(state = ScanState.SCANNING))

        val discovered = try {
            audioSource.query(directories).let { entries ->
                if (formatFilter.isNullOrEmpty()) {
                    entries
                } else {
                    entries.filter {
                        it.path.substringAfterLast('.', "").lowercase() in formatFilter
                    }
                }
            }
        } catch (error: SecurityException) {
            currentState = ScanState.ERROR
            return ScanResult(
                success = false,
                error = "Permission to read audio files was denied"
            )
        } catch (error: Exception) {
            currentState = ScanState.ERROR
            return ScanResult(
                success = false,
                error = "Could not read the media library: ${error.message}"
            )
        }

        if (isCancelled) {
            currentState = ScanState.CANCELLED
            return ScanResult(success = false, error = "Scan cancelled")
        }

        currentState = ScanState.PROCESSING

        val tracks = mutableListOf<Track>()
        var added = 0
        var updated = 0

        for ((index, entry) in discovered.withIndex()) {
            if (isCancelled) break

            val existing = existingTracks[entry.path]
            val isUnchanged = existing != null &&
                existing.lastModified == entry.lastModified &&
                existing.fileSize == entry.fileSize

            if (isUnchanged) {
                // Preserve the stored row, including its album id.
                tracks.add(existing)
            } else {
                tracks.add(buildTrack(entry, existingId = existing?.id ?: 0L))
                if (existing == null) added++ else updated++
            }

            progressCallback?.invoke(
                ScanProgress(
                    state = ScanState.PROCESSING,
                    filesFound = discovered.size,
                    filesProcessed = index + 1,
                    currentFile = entry.path,
                    tracksAdded = added,
                    tracksUpdated = updated
                )
            )
        }

        if (isCancelled) {
            currentState = ScanState.CANCELLED
            return ScanResult(success = false, error = "Scan cancelled")
        }

        val discoveredPaths = discovered.mapTo(mutableSetOf()) { it.path }

        // Removals are only meaningful for the scope that was actually scanned.
        // A folder-restricted scan never looks outside its prefixes, so treating
        // everything else as deleted would reduce the library to the selection.
        // A format-restricted scan is additive for the same reason: it did not
        // look at other formats, so it must not delete them.
        val removedPaths = if (!formatFilter.isNullOrEmpty()) {
            emptyList()
        } else {
            existingTracks.keys
                .filter { path -> isWithinScope(path, directories) }
                .filterNot { it in discoveredPaths }
        }

        currentState = ScanState.COMPLETED
        val duration = System.currentTimeMillis() - startTime

        progressCallback?.invoke(
            ScanProgress(
                state = ScanState.COMPLETED,
                filesFound = discovered.size,
                filesProcessed = discovered.size,
                tracksAdded = added,
                tracksUpdated = updated,
                tracksRemoved = removedPaths.size
            )
        )

        return ScanResult(
            success = true,
            tracksAdded = added,
            tracksUpdated = updated,
            tracksRemoved = removedPaths.size,
            totalTracks = tracks.size,
            durationMs = duration,
            tracks = tracks,
            removedPaths = removedPaths
        )
    }

    /**
     * Build a track for a single file that MediaStore may not have indexed.
     *
     * @return The track, or null if the file is unsupported or unreadable.
     */
    fun scanSingleFile(path: String): Track? {
        val metadata = metadataExtractor.extract(path) ?: return null
        val track = metadataExtractor.buildTrack(path, metadata)

        // Files reached this way are typically not in the media index, so the
        // decoder is the only source of exact format details.
        if (track.sampleRate > 0 && track.bitDepth > 0) return track

        val probed = formatProbe?.probe(path) ?: return track
        return track.copy(
            sampleRate = probed.sampleRate,
            bitDepth = probed.bitDepth,
            channels = probed.channels
        )
    }

    /**
     * Distinct folders containing audio, for folder selection in the UI.
     */
    fun discoverFolders(): List<FolderSummary> {
        return audioSource.query()
            .groupingBy { entry -> entry.path.substringBeforeLast('/', "") }
            .eachCount()
            .filterKeys { it.isNotEmpty() }
            .map { (path, count) -> FolderSummary(path, count) }
            .sortedBy { it.path }
    }

    /**
     * A folder containing audio files.
     */
    data class FolderSummary(
        val path: String,
        val trackCount: Int
    ) {
        val name: String
            get() = path.substringAfterLast('/').ifEmpty { path }
    }

    /**
     * Whether a stored path falls inside the scanned scope.
     *
     * An empty directory list means the whole device was scanned, so every
     * stored path is in scope.
     */
    private fun isWithinScope(path: String, directories: List<String>): Boolean {
        if (directories.isEmpty()) return true
        return directories.any { directory ->
            path.startsWith(directory.trimEnd('/') + "/")
        }
    }

    /**
     * Cancel the current scan operation.
     */
    fun cancel() {
        isCancelled = true
    }

    /**
     * Get the current scan state.
     */
    fun getState(): ScanState = currentState

    /**
     * Convert a MediaStore entry into a library track.
     *
     * Album id is resolved later when albums are aggregated, so it stays 0 here.
     */
    private fun buildTrack(
        entry: MediaStoreAudioSource.AudioFileEntry,
        existingId: Long
    ): Track {
        val fallbackTitle = entry.path.substringAfterLast('/').substringBeforeLast('.')

        // Only open the file when the media index left the format blank, which
        // is every track below Android 12. Probing is per-file I/O, so it is
        // limited to new and modified entries by the caller.
        val probed = if (entry.sampleRate <= 0 || entry.bitDepth <= 0) {
            formatProbe?.probe(entry.path)
        } else {
            null
        }

        return Track(
            id = existingId,
            path = entry.path,
            title = entry.title.ifBlank { fallbackTitle },
            artist = entry.artist,
            albumId = 0L,
            albumTitle = entry.album,
            // Fall back to the track artist so albums without an ALBUM_ARTIST
            // tag still group under a stable key.
            albumArtist = entry.albumArtist.ifBlank { entry.artist },
            genre = entry.genre,
            composer = entry.composer,
            trackNumber = entry.trackNumber,
            discNumber = entry.discNumber,
            duration = entry.durationMs,
            format = entry.format,
            sampleRate = entry.sampleRate.takeIf { it > 0 } ?: probed?.sampleRate ?: 0,
            bitDepth = entry.bitDepth.takeIf { it > 0 } ?: probed?.bitDepth ?: 0,
            channels = probed?.channels ?: 0,
            artworkPath = entry.artworkUri,
            year = entry.year,
            fileSize = entry.fileSize,
            lastModified = entry.lastModified,
            // A file that does not say who made it is a recording, ringtone or
            // voice note far more often than it is music, so it is quarantined
            // out of the main library instead of cluttering it. Never deleted:
            // the user can move entries in from Settings.
            isUnconfirmed = Track.looksUntagged(
                artist = entry.artist,
                albumArtist = entry.albumArtist.ifBlank { entry.artist }
            )
        )
    }
}
