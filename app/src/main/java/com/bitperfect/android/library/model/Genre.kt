package com.bitperfect.android.library.model

/**
 * Genre - represents a music genre.
 *
 * Not a database table. Genres are derived from the tracks table with a
 * GROUP BY query, so they can never drift out of sync with the library.
 */
data class Genre(
    val id: Long = 0,
    val name: String,
    val trackCount: Int = 0
) {
    val displayName: String
        get() = name.ifEmpty { "Unknown Genre" }
}
