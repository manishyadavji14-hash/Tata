package com.bitperfect.android.library.dao

import com.bitperfect.android.library.model.Playlist

/**
 * Data Access Object for Playlist entities.
 *
 * Provides CRUD operations for user-created playlists.
 */
// @Dao
interface PlaylistDao {

    // @Query("SELECT * FROM playlists ORDER BY modifiedAt DESC")
    fun getAll(): List<Playlist>

    // @Query("SELECT * FROM playlists WHERE id = :id")
    fun getById(id: Long): Playlist?

    // @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(playlist: Playlist): Long

    // @Update
    fun update(playlist: Playlist)

    // @Delete
    fun delete(playlist: Playlist)

    // @Query("DELETE FROM playlists WHERE id = :id")
    fun deleteById(id: Long)

    // @Query("SELECT COUNT(*) FROM playlists")
    fun count(): Int

    // @Query("SELECT * FROM playlists WHERE name LIKE '%' || :query || '%'")
    fun search(query: String): List<Playlist>
}
