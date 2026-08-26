package com.bitperfect.android.library.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Artist entity - represents a music artist.
 */
@Entity(
    tableName = "artists",
    indices = [Index(value = ["name"], unique = true)]
)
data class Artist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0
) {
    val displayName: String
        get() = name.ifEmpty { "Unknown Artist" }
}
