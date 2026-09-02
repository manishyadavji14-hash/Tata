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
    val isUnconfirmed: Boolean = false,
    /**
     * When this file was first added to the library, in epoch milliseconds.
     *
     * Deliberately not [lastModified]: that is the file's own timestamp, so a
     * freshly copied album full of decade-old files would sort as old, and
     * editing a file would move it to the top of "recently added". Rows that
     * predate this column are backfilled from [lastModified] by MIGRATION_3_4,
     * which is the best approximation available after the fact.
     */
    @ColumnInfo(defaultValue = "0")
    val addedAt: Long = 0,
    /**
     * Total milliseconds of this track actually listened to, across every play.
     *
     * Accumulates rather than resetting, so it can exceed [duration]: playing a
     * four-minute track once and then replaying one minute of it gives 300000,
     * which is 125% of the track. That is what "most played" sorts on, so a
     * track heard twice ranks above one heard once and abandoned halfway.
     *
     * Seeks are excluded — see PlayStatsRecorder.
     */
    @ColumnInfo(defaultValue = "0")
    val playedMs: Long = 0,
    /**
     * Set once the user has edited this row's tags in the app.
     *
     * A rescan reads the file's tags, which have not changed, and would
     * therefore overwrite the correction on the next scan. This flag makes
     * persistScanResult keep the user's descriptive fields while still
     * refreshing the technical ones.
     */
    @ColumnInfo(defaultValue = "0")
    val isUserEdited: Boolean = false
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

    /**
     * Share of the track listened to, as a percentage, summed over every play.
     *
     * Can exceed 100: see [playedMs]. Zero when the duration is unknown, since
     * a percentage of an unknown length would be meaningless rather than large.
     */
    val playedPercent: Int
        get() = if (duration > 0) ((playedMs * 100) / duration).toInt() else 0

    companion object {
        /**
         * Values a tagger writes to mean "there is no artist here".
         *
         * MediaStore's own sentinel is `<unknown>`, which
         * MediaStoreAudioSource already maps to empty, but plenty of files carry
         * the words as a literal tag instead — that is what a ripper writes when
         * it could not identify the disc, and what a recorder app writes for a
         * voice note. Treating them as text would put every one of those in the
         * library under an artist called "Unknown Artist".
         */
        private val MISSING_ARTIST_VALUES = setOf(
            "",
            "<unknown>",
            "unknown",
            "unknown artist",
            "various",
            "various artists"
        )

        /** Whether an artist tag carries no actual attribution. */
        fun isMissingArtist(value: String): Boolean =
            value.trim().lowercase() in MISSING_ARTIST_VALUES

        /**
         * Whether a file looks like it is not music, judged only on tags.
         *
         * **The rule is the artist.** A file with no artist attribution is not
         * something the user chose to keep as music: it is a voice note, a
         * ringtone, a WhatsApp clip, a podcast download or a recording. Music
         * that someone deliberately put on their phone essentially always says
         * who made it.
         *
         * Both the track artist and the album artist have to be missing, so a
         * compilation track that only names its album artist still counts as
         * music. `albumTitle`, `year` and artwork are deliberately *not*
         * considered: an untagged recording sitting in a folder that MediaStore
         * happened to attach a folder image to would otherwise walk straight
         * into the library, which is the exact complaint this rule answers.
         *
         * `title` is not considered either — the scanner falls back to the file
         * name, so it is never empty and carries no signal.
         *
         * Nothing is deleted. Quarantined files are listed under Settings ->
         * "Review unconfirmed music", where moving one into the library is
         * permanent.
         *
         * This is stricter than the original rule, which required *every* tag to
         * be absent. That version let anything with a stray year or a piece of
         * folder artwork through, so recordings kept appearing in the library.
         */
        fun looksUntagged(artist: String, albumArtist: String): Boolean =
            isMissingArtist(artist) && isMissingArtist(albumArtist)

        /** Convenience overload for an already-built track. */
        fun looksUntagged(track: Track): Boolean = looksUntagged(
            artist = track.artist,
            albumArtist = track.albumArtist
        )
    }
}
