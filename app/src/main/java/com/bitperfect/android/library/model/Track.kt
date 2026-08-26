package com.bitperfect.android.library.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Track entity - represents a single audio file in the library.
 *
 * Room entity with proper indices for common queries.
 * Stores both file metadata and audio format information.
 *
 * The computed properties below (isHighRes, isDsd, displayBitrate, folder)
 * have no backing field, so Room does not persist them.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["artist"]),
        Index(value = ["genre"]),
        Index(value = ["composer"]),
        Index(value = ["path"], unique = true)
    ]
)
data class Track(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val path: String,
    val title: String,
    val artist: String = "",
    val albumId: Long = 0,
    val albumTitle: String = "",
    /** Album artist, used to group compilations under one album. */
    val albumArtist: String = "",
    val genre: String = "",
    val composer: String = "",
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val duration: Long = 0,           // Duration in milliseconds
    val format: String = "",           // WAV, FLAC, DSF, etc.
    val sampleRate: Int = 0,           // Sample rate in Hz
    val bitDepth: Int = 0,             // Bits per sample
    val channels: Int = 0,
    val artworkPath: String? = null,   // Path to extracted artwork
    val lyrics: String? = null,
    val year: Int = 0,
    val fileSize: Long = 0,
    val lastModified: Long = 0,        // File last modified timestamp
    /** User-marked favourite. Survives rescans because track ids are stable. */
    val isFavourite: Boolean = false
) {
    val isHighRes: Boolean
        get() = sampleRate > 48000 || bitDepth > 16

    val isDsd: Boolean
        get() = format.equals("DSF", ignoreCase = true) ||
                format.equals("DFF", ignoreCase = true)

    val displayBitrate: String
        get() = if (isDsd) {
            when {
                sampleRate >= 11289600 -> "DSD256"
                sampleRate >= 5644800 -> "DSD128"
                else -> "DSD64"
            }
        } else {
            "${sampleRate / 1000}kHz/${bitDepth}bit"
        }

    val folder: String
        get() {
            val lastSep = path.lastIndexOf('/')
            return if (lastSep > 0) path.substring(0, lastSep) else ""
        }
}
