package com.bitperfect.android.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.bitperfect.android.library.dao.AlbumDao
import com.bitperfect.android.library.dao.ArtistDao
import com.bitperfect.android.library.dao.PlaylistDao
import com.bitperfect.android.library.dao.TrackDao
import com.bitperfect.android.library.model.Album
import com.bitperfect.android.library.model.Artist
import com.bitperfect.android.library.model.Playlist
import com.bitperfect.android.library.model.Track

/**
 * Room database for the music library.
 *
 * Tables: tracks, albums, artists, playlists.
 *
 * Genres and composers are intentionally not tables. They are derived from
 * the tracks table with GROUP BY queries (see TrackDao), which keeps them
 * consistent with the library without extra bookkeeping during scans.
 *
 * Schema version history:
 * - v1: tracks, albums, artists, playlists
 */
@Database(
    entities = [Track::class, Album::class, Artist::class, Playlist::class],
    version = 1,
    exportSchema = true
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val DATABASE_NAME = "bitperfect_library.db"

        @Volatile
        private var instance: LibraryDatabase? = null

        /**
         * Get the process-wide database instance.
         *
         * The library is a rebuildable cache of on-device files, so a
         * destructive fallback is acceptable: a rescan restores it.
         */
        fun getInstance(context: Context): LibraryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
