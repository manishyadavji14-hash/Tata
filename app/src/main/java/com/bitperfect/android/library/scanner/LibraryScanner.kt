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
    private val formatProbe: AudioFormatProbe? = null,
    /**
     * Finds a cover that can actually be displayed, given the file path and
     * whatever MediaStore offered.
     *
     * Needed because the MediaStore album-art URI is deprecated and usually
     * resolves to nothing on current Android, so recording it produced a library
     * with no visible artwork anywhere. Optional: without it the MediaStore URI is
     * stored as before.
     *
     * Results are cached by file identity, so a rescan does not re-read covers it
     * has already extracted.
     */
    private val artworkResolver: ((audioPath: String, mediaStoreUri: String?) -> String?)? = null,
    /**
     * The file's real size and modification time, or null when it cannot be read.
     *
     * Injected so the freshness rules can be exercised without a filesystem, and
     * defaulted to the filesystem itself. It exists at all because the scan used to
     * decide whether a file had changed by comparing the media index against itself;
     * see [TrackFreshness].
     */
    private val fileFacts: (String) -> TrackFreshness.FileFacts? = { path ->
        val file = java.io.File(path)
        if (file.isFile) {
            TrackFreshness.FileFacts(
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
        } else {
            null
        }
    }
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

            // Read once per file and shared by both decisions below: whether the
            // stored row is still accurate, and whether the media index is.
            val onDisk = fileFacts(entry.path)

            val isUnchanged = existing != null &&
                TrackFreshness.isRowCurrent(
                    storedSizeBytes = existing.fileSize,
                    storedModifiedMs = existing.lastModified,
                    onDisk = onDisk
                )

            if (isUnchanged) {
                // Preserve the stored row, including its album id.
                tracks.add(existing)
            } else {
                tracks.add(
                    buildTrack(
                        entry = entry,
                        existingId = existing?.id ?: 0L,
                        existing = existing,
                        onDisk = onDisk
                    )
                )
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
            channels = probed.channels,
            // Taken from the decoder when the platform retriever gave none. A track
            // with no duration shows a dead progress bar with no end to it.
            duration = TrackFreshness.preferMeasuredDuration(
                measuredMs = probed.durationMs,
                indexedMs = 0L,
                storedMs = track.duration
            )
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
        existingId: Long,
        existing: Track? = null,
        onDisk: TrackFreshness.FileFacts? = null
    ): Track {
        val fallbackTitle = entry.path.substringAfterLast('/').substringBeforeLast('.')

        // Whether the media index's own numbers can be believed for this file.
        val indexCurrent = TrackFreshness.isIndexCurrent(
            indexedSizeBytes = entry.fileSize,
            indexedModifiedMs = entry.lastModified,
            onDisk = onDisk
        )

        // Open the file when the index left the format blank — every track below
        // Android 12 — and also when the index is demonstrably describing a
        // different file. That second case is the one that mattered: the index
        // reported a rate and depth with full confidence, so the probe was skipped
        // and its stale answer was written to the library unchallenged, scan after
        // scan. Probing is per-file I/O, which is why it stays conditional.
        val probed = if (!indexCurrent || entry.sampleRate <= 0 || entry.bitDepth <= 0) {
            formatProbe?.probe(entry.path)
        } else {
            null
        }

        // Once the index is known to be wrong about the file, its technical fields
        // are not evidence about it either.
        val indexedSampleRate = if (indexCurrent) entry.sampleRate else 0
        val indexedBitDepth = if (indexCurrent) entry.bitDepth else 0
        val indexedDuration = if (indexCurrent) entry.durationMs else 0L

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
            // Measured beats indexed beats stored, and nothing is ever overwritten
            // with zero — the rule the artwork path already had, which the technical
            // fields did not: one scan where the probe could not open a file used to
            // empty the format text for that track.
            duration = TrackFreshness.preferMeasuredDuration(
                measuredMs = probed?.durationMs ?: 0L,
                indexedMs = indexedDuration,
                storedMs = existing?.duration ?: 0L
            ),
            format = entry.format,
            sampleRate = TrackFreshness.preferMeasured(
                measured = probed?.sampleRate ?: 0,
                indexed = indexedSampleRate,
                stored = existing?.sampleRate ?: 0
            ),
            bitDepth = TrackFreshness.preferMeasured(
                measured = probed?.bitDepth ?: 0,
                indexed = indexedBitDepth,
                stored = existing?.bitDepth ?: 0
            ),
            channels = TrackFreshness.preferMeasured(
                measured = probed?.channels ?: 0,
                indexed = 0,
                stored = existing?.channels ?: 0
            ),
            // Prefer a cover that can be shown over the one MediaStore names but
            // cannot open.
            artworkPath = artworkResolver?.invoke(entry.path, entry.artworkUri)
                ?: entry.artworkUri,
            year = entry.year,
            // Recorded from the filesystem when it can be read, so the next scan
            // compares the row against the file rather than against the media
            // index's record of the file. Storing the index's values is what made
            // the freshness test self-confirming.
            fileSize = onDisk?.sizeBytes?.takeIf { it > 0L } ?: entry.fileSize,
            lastModified = onDisk?.lastModifiedMs?.takeIf { it > 0L } ?: entry.lastModified,
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
