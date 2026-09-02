package com.bitperfect.android.library

import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.bitperfect.android.library.model.Track
import java.io.File

/**
 * MetadataExtractor - extracts audio metadata from audio files.
 *
 * Two paths exist, because opening every file during a scan is slow:
 *
 * 1. Bulk scanning reads tags straight from the MediaStore index
 *    (see MediaStoreAudioSource) and never touches this class per file.
 * 2. This class handles the cases MediaStore cannot answer - files that are
 *    not indexed, and embedded artwork - using MediaMetadataRetriever.
 *
 * Note on bit depth: sample rate and bits per sample are only exposed by the
 * platform from Android 12 (API 31). Below that they stay zero here, and the
 * native decoder supplies exact values when a track is actually played.
 */
class MetadataExtractor {

    companion object {
        private const val TAG = "MetadataExtractor"

        /** Audio file extensions recognized by the scanner. */
        val SUPPORTED_EXTENSIONS = setOf(
            "wav", "wave",
            "flac",
            "dsf",
            "dff",
            "aiff", "aif",
            "alac", "m4a",
            "ape",
            "mp3",
            "aac",
            "ogg", "oga",
            "opus",
            "wv"   // WavPack
        )

        /**
         * Check if a file extension is a supported audio format.
         */
        fun isSupportedExtension(extension: String): Boolean {
            return extension.lowercase() in SUPPORTED_EXTENSIONS
        }

        /**
         * Human-readable format name for a file extension.
         */
        fun formatForExtension(extension: String): String = when (extension.lowercase()) {
            "wav", "wave" -> "WAV"
            "flac" -> "FLAC"
            "dsf" -> "DSF"
            "dff" -> "DFF"
            "aiff", "aif" -> "AIFF"
            "alac" -> "ALAC"
            "m4a" -> "M4A"
            "ape" -> "APE"
            "mp3" -> "MP3"
            "aac" -> "AAC"
            "ogg", "oga" -> "OGG"
            "opus" -> "OPUS"
            "wv" -> "WavPack"
            else -> extension.uppercase()
        }
    }

    /**
     * Extracted metadata result.
     */
    data class Metadata(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        val genre: String = "",
        val composer: String = "",
        val trackNumber: Int = 0,
        val discNumber: Int = 1,
        val year: Int = 0,
        val duration: Long = 0,         // milliseconds
        val sampleRate: Int = 0,
        val bitDepth: Int = 0,
        val channels: Int = 0,
        val format: String = "",
        /**
         * Always null, and intentionally so. `MediaMetadataRetriever` has no
         * lyrics key, and lyrics are not cached in the library row because a copy
         * would go stale when the user retags the file. [EmbeddedLyricsReader]
         * reads them from the file on demand instead; the player goes through
         * `LyricsRepository`, never through this field.
         */
        val lyrics: String? = null,
        val hasArtwork: Boolean = false
    )

    /**
     * Extract metadata from an audio file.
     *
     * @param path Full file path
     * @return Extracted metadata, or null if the file is not a supported
     *   audio format or cannot be read.
     */
    fun extract(path: String): Metadata? {
        val extension = path.substringAfterLast('.', "")
        if (!isSupportedExtension(extension)) return null

        val format = formatForExtension(extension)
        val file = File(path)
        val fallbackTitle = file.nameWithoutExtension

        // Format and title come from the path alone, so an absent or unreadable
        // file still yields a usable entry rather than disappearing.
        if (!file.isFile) {
            return Metadata(title = fallbackTitle, format = format)
        }

        // DSD formats are not readable by the platform retriever. The native
        // engine owns DSF parsing, so report what can be known from the file.
        if (format == "DSF" || format == "DFF") {
            return Metadata(title = fallbackTitle, format = format)
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)

            Metadata(
                title = retriever.string(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: fallbackTitle,
                artist = retriever.string(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = retriever.string(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                albumArtist = retriever
                    .string(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    .orEmpty(),
                genre = retriever.string(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
                composer = retriever.string(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    .orEmpty(),
                trackNumber = retriever.leadingInt(
                    MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER
                ),
                discNumber = retriever
                    .leadingInt(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    .coerceAtLeast(1),
                year = retriever.year(),
                duration = retriever
                    .string(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                sampleRate = retriever.sampleRate(),
                bitDepth = retriever.bitsPerSample(),
                channels = 0,
                format = format,
                hasArtwork = retriever.embeddedPicture != null
            )
        } catch (error: Exception) {
            // Unreadable or malformed file: still surface it with a usable name
            // rather than dropping it from the library entirely.
            Log.w(TAG, "Could not read metadata from $path: ${error.message}")
            Metadata(title = fallbackTitle, format = format)
        } finally {
            releaseQuietly(retriever)
        }
    }

    /**
     * Extract DSD metadata.
     *
     * DSF tags live in an ID3v2 block that the platform retriever cannot read;
     * native parsing is required, which is not yet implemented.
     */
    fun extractDsfMetadata(path: String): Metadata? = extract(path)

    /**
     * Extract embedded artwork from an audio file into a cache.
     *
     * Only needed for files MediaStore has not indexed; for indexed files the
     * album artwork content URI is used directly and nothing is written.
     *
     * A cache hit avoids opening the file at all. Entries are keyed on the
     * source's size and modification time, so editing a file's tags produces a
     * miss rather than serving its previous cover.
     *
     * @return Path to the cached artwork, or null if none is embedded.
     */
    fun extractArtwork(audioPath: String, cache: ArtworkCache): String? {
        val sourceFile = File(audioPath)
        if (!sourceFile.isFile) return null

        cache.find(sourceFile)?.let { return it }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioPath)
            val picture = retriever.embeddedPicture ?: return null
            cache.put(sourceFile, picture)
        } catch (error: Exception) {
            Log.w(TAG, "Could not extract artwork from $audioPath: ${error.message}")
            null
        } finally {
            releaseQuietly(retriever)
        }
    }

    /**
     * Build a Track entity from extracted metadata.
     */
    fun buildTrack(path: String, metadata: Metadata, albumId: Long = 0): Track {
        val file = File(path)
        val fileName = path.substringAfterLast('/')
        return Track(
            path = path,
            title = metadata.title.ifEmpty { fileName.substringBeforeLast('.') },
            artist = metadata.artist,
            albumId = albumId,
            albumTitle = metadata.album,
            // Fall back to the track artist so albums without an album-artist
            // tag still group under a stable key.
            albumArtist = metadata.albumArtist.ifBlank { metadata.artist },
            genre = metadata.genre,
            composer = metadata.composer,
            trackNumber = metadata.trackNumber,
            discNumber = metadata.discNumber,
            duration = metadata.duration,
            format = metadata.format,
            sampleRate = metadata.sampleRate,
            bitDepth = metadata.bitDepth,
            channels = metadata.channels,
            year = metadata.year,
            lyrics = metadata.lyrics,
            fileSize = file.length().takeIf { it > 0 } ?: 0L,
            lastModified = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }

    // --- MediaMetadataRetriever helpers ---

    private fun MediaMetadataRetriever.string(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Tag values such as "3/12" carry a total after the separator.
     */
    private fun MediaMetadataRetriever.leadingInt(key: Int): Int {
        val raw = string(key) ?: return 0
        return raw.substringBefore('/').trim().toIntOrNull() ?: 0
    }

    /**
     * Year, falling back to the leading year of a full date tag.
     */
    private fun MediaMetadataRetriever.year(): Int {
        string(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()?.let { return it }
        val date = string(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return 0
        return Regex("""\d{4}""").find(date)?.value?.toIntOrNull() ?: 0
    }

    private fun MediaMetadataRetriever.sampleRate(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
        return string(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
    }

    private fun MediaMetadataRetriever.bitsPerSample(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
        return string(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
    }

    /**
     * MediaMetadataRetriever is AutoCloseable from API 29, which is minSdk.
     */
    private fun releaseQuietly(retriever: MediaMetadataRetriever) {
        try {
            retriever.close()
        } catch (error: Exception) {
            Log.w(TAG, "Retriever release failed: ${error.message}")
        }
    }
}
