package com.bitperfect.android.library.model

import androidx.room.ColumnInfo
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
    val isFavourite: Boolean = false,
    /**
     * Set when the file carries no identifying tags at all, so it is probably a
     * recording, voice note or ringtone rather than music.
     *
     * Quarantined out of the main library but never deleted; the user can move
     * entries into the library from Settings, which clears this permanently. See
     * [looksUntagged] for the rule.
     *
     * The default is declared here as well as in MIGRATION_2_3 so the two cannot
     * disagree. Room only validates a column default when the entity states one,
     * so without this the migration's `DEFAULT 0` would go unchecked.
     */
    @ColumnInfo(defaultValue = "0")
    val isUnconfirmed: Boolean = false
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

    companion object {
        /**
         * Whether a file looks like it is not music, judged only on tags.
         *
         * Real music almost always carries at least one of an album, an artist, a
         * year or embedded artwork. Recordings, voice notes, ringtones and
         * WhatsApp audio carry none.
         *
         * Every field must be empty for this to be true. The conjunction is
         * deliberate and conservative: an album track missing only its year, or a
         * single with no album, stays in the library. The cost of a false positive
         * — real music hidden from the user — is far worse than a stray recording
         * appearing in the list.
         *
         * `title` is deliberately not considered: the scanner falls back to the
         * file name, so it is never empty and carries no signal.
         */
        fun looksUntagged(
            artist: String,
            albumTitle: String,
            albumArtist: String,
            year: Int,
            artworkPath: String?
        ): Boolean =
            artist.isBlank() &&
                albumTitle.isBlank() &&
                albumArtist.isBlank() &&
                year <= 0 &&
                artworkPath.isNullOrBlank()

        /** Convenience overload for an already-built track. */
        fun looksUntagged(track: Track): Boolean = looksUntagged(
            artist = track.artist,
            albumTitle = track.albumTitle,
            albumArtist = track.albumArtist,
            year = track.year,
            artworkPath = track.artworkPath
        )
    }
}
