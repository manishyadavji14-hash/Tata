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
    indices = [Index(value = ["title", "artist"], unique = true)]
)
data class Album(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String = "",
    val artworkPath: String? = null,
    val year: Int = 0,
    val trackCount: Int = 0,
    val totalDuration: Long = 0        // Total duration in milliseconds
) {
    val displayArtist: String
        get() = artist.ifEmpty { "Unknown Artist" }

    val displayYear: String
        get() = if (year > 0) year.toString() else ""
}
