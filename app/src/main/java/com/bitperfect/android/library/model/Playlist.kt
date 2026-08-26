package com.bitperfect.android.library.model

/**
 * Playlist entity - user-created playlist.
 *
 * Track IDs are stored as a JSON array for flexibility in ordering.
 * The createdAt and modifiedAt timestamps enable sorting by date.
 */
// @Entity(tableName = "playlists")
data class Playlist(
    // @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val trackIds: String = "[]",       // JSON array of track IDs
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
) {
    /**
     * Parse track IDs from JSON string.
     */
    fun getTrackIdList(): List<Long> {
        return try {
            trackIds.removeSurrounding("[", "]")
                .split(",")
                .filter { it.isNotBlank() }
                .map { it.trim().toLong() }
        } catch (e: NumberFormatException) {
            emptyList()
        }
    }

    /**
     * Get the track count from the stored IDs.
     */
    val trackCount: Int
        get() = getTrackIdList().size

    companion object {
        /**
         * Create a track IDs JSON string from a list of IDs.
         */
        fun trackIdsToJson(ids: List<Long>): String {
            return ids.joinToString(",", "[", "]")
        }
    }
}
