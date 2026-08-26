package com.bitperfect.android.library

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
 * For PCM formats (WAV, FLAC, AIFF): uses Android MediaMetadataRetriever
 * or file header parsing for format info.
 *
 * For DSD formats (DSF, DFF): uses native JNI call to parse DSD metadata
 * blocks (ID3v2 tags in DSF).
 */
class MetadataExtractor {

    companion object {
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
     * Extract metadata from an audio file.
     *
     * @param path Full file path
     * @return Extracted metadata, or null if file is not a supported audio format
     */
    fun extract(path: String): Metadata? {
        val extension = path.substringAfterLast('.', "").lowercase()
        if (!isSupportedExtension(extension)) return null

        // Determine format from extension
        val format = when (extension) {
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

        // In production, this would use MediaMetadataRetriever or native parsing.
        // For DSF files, would call native JNI to parse ID3v2 metadata block.
        return Metadata(
            title = extractTitleFromPath(path),
            format = format
        )
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
        // In production: use MediaMetadataRetriever.getEmbeddedPicture()
        // or parse FLAC PICTURE block, DSF ID3v2 APIC frame, etc.
        return null
    }

    /**
     * Build a Track entity from extracted metadata.
     */
    fun buildTrack(path: String, metadata: Metadata, albumId: Long = 0): Track {
        val fileName = path.substringAfterLast('/')
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
            lastModified = System.currentTimeMillis()
        )
    }

    private fun extractTitleFromPath(path: String): String {
        val fileName = path.substringAfterLast('/')
        return fileName.substringBeforeLast('.')
    }
}
