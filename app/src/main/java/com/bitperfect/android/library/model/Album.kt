package com.bitperfect.android.library.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Album entity - represents a music album.
 *
 * Albums are identified by their title + artist combination.
 */
@Entity(
    tableName = "albums",
    indices = [Index(value = ["title", "albumArtist"], unique = true)]
)
data class Album(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    /**
     * Album artist, which is what an album is actually identified by.
     *
     * Grouping on the per-track artist splits compilations into one album per
     * featured artist, so this is the grouping key and [artist] is only kept
     * for display when every track shares one artist.
     */
    val albumArtist: String = "",
    val artist: String = "",
    val artworkPath: String? = null,
    val year: Int = 0,
    val trackCount: Int = 0,
    val totalDuration: Long = 0        // Total duration in milliseconds
) {
    val displayArtist: String
        get() = albumArtist.ifEmpty { artist }.ifEmpty { "Unknown Artist" }

    val displayYear: String
        get() = if (year > 0) year.toString() else ""

    /** True when tracks credit different artists, i.e. a compilation. */
    val isCompilation: Boolean
        get() = artist.isEmpty() && albumArtist.isNotEmpty()
}
