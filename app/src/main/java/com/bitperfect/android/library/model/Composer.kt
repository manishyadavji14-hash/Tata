package com.bitperfect.android.library.model

/**
 * Composer entity - represents a music composer.
 */
// @Entity(tableName = "composers", indices = [
//     Index(value = ["name"], unique = true)
// ])
data class Composer(
    // @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val trackCount: Int = 0
) {
    val displayName: String
        get() = name.ifEmpty { "Unknown Composer" }
}
