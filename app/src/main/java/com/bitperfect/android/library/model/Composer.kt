package com.bitperfect.android.library.model

/**
 * Composer - represents a music composer.
 *
 * Not a database table. Composers are derived from the tracks table with a
 * GROUP BY query, so they can never drift out of sync with the library.
 */
data class Composer(
    val id: Long = 0,
    val name: String,
    val trackCount: Int = 0
) {
    val displayName: String
        get() = name.ifEmpty { "Unknown Composer" }
}
