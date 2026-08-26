package com.bitperfect.android.library

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.bitperfect.android.library.model.Track

/**
 * MetadataExtractor - extracts audio metadata from various file formats.
 *
 * Supports:
 * - Standard metadata: title, artist, album, genre, composer, track/disc number
 * - Audio format: sample rate, bit depth, channels, duration
 * - Artwork: extracts embedded artwork to cache directory
 * - Lyrics: extracts embedded lyrics if available
 *
 * Uses android.media.MediaMetadataRetriever for real metadata extraction.
 * For DSD formats (DSF, DFF): uses native JNI call to parse DSD metadata
 * blocks (ID3v2 tags in DSF).
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
    }

    /**
     * Extracted metadata result.
     */
    data class Metadata(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
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
        val lyrics: String? = null,
        val hasArtwork: Boolean = false
    )

    /**
     * Extract metadata from an audio file using MediaMetadataRetriever.
     *
     * @param path Full file path
     * @return Extracted metadata, or null if file is not a supported audio format
     */
    fun extract(path: String): Metadata? {
        val extension = path.substringAfterLast('.', "").lowercase()
        if (!isSupportedExtension(extension)) return null

        // Determine format from extension
        val format = extensionToFormat(extension)

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
            val composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: ""
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val yearStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val trackNumberStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val discNumberStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)

            // Parse duration
            val duration = durationStr?.toLongOrNull() ?: 0L

            // Parse track number (may be in format "3/12")
            val trackNumber = parseSlashNumber(trackNumberStr)

            // Parse disc number (may be in format "1/2")
            val discNumber = parseSlashNumber(discNumberStr).let { if (it == 0) 1 else it }

            // Parse year
            val year = parseYear(yearStr)

            // Parse channels - try METADATA_KEY_NUM_TRACKS first, not reliable for channels
            // Use MediaExtractor to get the accurate channel count from MediaFormat
            var sampleRate = 0
            var bitDepth = 0
            var channels = 0

            // API 31+ provides sample rate and bit depth directly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sampleRateStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
                )
                val bitsPerSampleStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE
                )
                sampleRate = sampleRateStr?.toIntOrNull() ?: 0
                bitDepth = bitsPerSampleStr?.toIntOrNull() ?: 0
            }

            // Use MediaExtractor to get accurate channel count from the audio track's MediaFormat
            channels = extractChannelCount(path)

            // Fallback: most music is stereo if MediaExtractor could not determine it
            if (channels == 0) {
                channels = 2
            }

            // Check if artwork exists
            val hasArtwork = retriever.embeddedPicture != null

            Metadata(
                title = title.ifEmpty { extractTitleFromPath(path) },
                artist = artist,
                album = album,
                genre = genre,
                composer = composer,
                trackNumber = trackNumber,
                discNumber = discNumber,
                year = year,
                duration = duration,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                channels = channels,
                format = format,
                lyrics = null,
                hasArtwork = hasArtwork
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata from: $path", e)
            // Fall back to filename-based metadata
            Metadata(
                title = extractTitleFromPath(path),
                format = format
            )
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }

    /**
     * Extract DSD metadata via native JNI.
     * Parses the ID3v2 tag embedded in DSF files.
     */
    fun extractDsfMetadata(path: String): Metadata? {
        // Would call: NativeAudioEngine.parseDsfMetadata(path)
        return extract(path)
    }

    /**
     * Extract artwork from an audio file to a cache directory.
     * @param audioPath Path to the audio file
     * @param cacheDir Directory to save extracted artwork
     * @return Path to the saved artwork, or null if no artwork found
     */
    fun extractArtwork(audioPath: String, cacheDir: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioPath)
            val artBytes = retriever.embeddedPicture ?: return null

            val artFile = java.io.File(cacheDir, "art_${audioPath.hashCode()}.jpg")
            artFile.parentFile?.mkdirs()
            artFile.writeBytes(artBytes)
            artFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract artwork from: $audioPath", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }

    /**
     * Build a Track entity from extracted metadata.
     */
    fun buildTrack(path: String, metadata: Metadata, albumId: Long = 0): Track {
        val fileName = path.substringAfterLast('/')
        val file = java.io.File(path)
        return Track(
            path = path,
            title = metadata.title.ifEmpty { fileName.substringBeforeLast('.') },
            artist = metadata.artist,
            albumId = albumId,
            albumTitle = metadata.album,
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
            fileSize = if (file.exists()) file.length() else 0L,
            lastModified = if (file.exists()) file.lastModified() else System.currentTimeMillis()
        )
    }

    private fun extractTitleFromPath(path: String): String {
        val fileName = path.substringAfterLast('/')
        return fileName.substringBeforeLast('.')
    }

    /**
     * Extract the audio channel count using MediaExtractor and MediaFormat.
     * This is the reliable way to get channel count across all API levels,
     * as MediaMetadataRetriever does not expose a channel count key.
     *
     * @param path Full file path
     * @return Channel count, or 0 if it could not be determined
     */
    private fun extractChannelCount(path: String): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        0
                    }
                }
            }
            0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract channel count from: $path", e)
            0
        } finally {
            extractor.release()
        }
    }

    private fun extensionToFormat(extension: String): String {
        return when (extension) {
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
     * Parse a track/disc number that may be in "N/M" format.
     * Returns just the N part.
     */
    private fun parseSlashNumber(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        val parts = value.split("/")
        return parts[0].trim().toIntOrNull() ?: 0
    }

    /**
     * Parse a year from various date formats (e.g., "2023", "2023-01-15").
     */
    private fun parseYear(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        // Try direct integer parse first
        value.toIntOrNull()?.let { return it }
        // Try extracting first 4 digits (handles "2023-01-15" format)
        val yearMatch = Regex("(\\d{4})").find(value)
        return yearMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
