package com.bitperfect.android.library.model

/**
 * Genre entity - represents a music genre.
 */
// @Entity(tableName = "genres", indices = [
//     Index(value = ["name"], unique = true)
// ])
data class Genre(
    // @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val trackCount: Int = 0
) {
    val displayName: String
        get() = name.ifEmpty { "Unknown Genre" }
}
