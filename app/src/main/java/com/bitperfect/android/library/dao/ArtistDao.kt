package com.bitperfect.android.library.dao

import com.bitperfect.android.library.model.Artist

/**
 * Data Access Object for Artist entities.
 *
 * Provides query methods for browsing and searching artists.
 */
// @Dao
interface ArtistDao {

    // @Query("SELECT * FROM artists ORDER BY name ASC")
    fun getAll(): List<Artist>

    // @Query("SELECT * FROM artists WHERE id = :id")
    fun getById(id: Long): Artist?

    // @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%'")
    fun search(query: String): List<Artist>

    // @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(artist: Artist): Long

    // @Update
    fun update(artist: Artist)

    // @Delete
    fun delete(artist: Artist)

    // @Query("SELECT * FROM artists WHERE name = :name LIMIT 1")
    fun findByName(name: String): Artist?

    // @Query("SELECT COUNT(*) FROM artists")
    fun count(): Int
}
