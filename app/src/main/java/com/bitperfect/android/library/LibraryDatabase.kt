package com.bitperfect.android.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * - v2: album artist grouping (Album.albumArtist, Track.albumArtist) and
 *   Track.isFavourite
 * - v3: Track.isUnconfirmed, quarantining files that carry no tags
 * - v4: Track.addedAt, Track.playedMs and Track.isUserEdited, backing the
 *   date-added and most-played sort orders and library-only tag edits. Also
 *   re-applies quarantine under the stricter artist-based rule.
 */
@Database(
    entities = [Track::class, Album::class, Artist::class, Playlist::class],
    version = 4,
    exportSchema = true
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val DATABASE_NAME = "bitperfect_library.db"

        /**
         * v1 to v2: album artist grouping and favourites.
         *
         * Tracks and playlists are migrated rather than dropped. Playlists
         * reference tracks by id and favourites are user data, so neither can
         * be rebuilt by rescanning.
         *
         * The albums table is derived entirely from tracks and is rebuilt on
         * every scan, so it is recreated outright - its unique index changes
         * from (title, artist) to (title, albumArtist), which SQLite cannot
         * express as an ALTER.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `albumArtist` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `isFavourite` INTEGER NOT NULL DEFAULT 0"
                )

                // Seed the new grouping key from the existing track artist so
                // the library stays browsable before the next rescan.
                db.execSQL("UPDATE `tracks` SET `albumArtist` = `artist`")

                db.execSQL("DROP TABLE IF EXISTS `albums`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `albums` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`albumArtist` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`artworkPath` TEXT, " +
                        "`year` INTEGER NOT NULL, " +
                        "`trackCount` INTEGER NOT NULL, " +
                        "`totalDuration` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_albums_title_albumArtist` " +
                        "ON `albums` (`title`, `albumArtist`)"
                )

                // Album ids were just discarded; a rescan re-links them.
                db.execSQL("UPDATE `tracks` SET `albumId` = 0")
            }
        }

        /**
         * v2 to v3: quarantine files that carry no identifying tags.
         *
         * Additive only — one column with a default, so no table is rebuilt and
         * no user data is at risk. Playlists, favourites and album ids are
         * untouched.
         *
         * Existing rows are then backfilled with the same rule the scanner
         * applies, so an established library is cleaned up on upgrade instead of
         * having to wait for a rescan. The condition is kept in SQL rather than
         * loading every row into memory; it mirrors Track.looksUntagged, and the
         * unit tests assert the two agree.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `isUnconfirmed` INTEGER NOT NULL DEFAULT 0"
                )

                // TRIM guards against tags that are whitespace rather than empty,
                // which isBlank() also treats as absent.
                db.execSQL(
                    """
                    UPDATE `tracks` SET `isUnconfirmed` = 1
                    WHERE TRIM(`artist`) = ''
                      AND TRIM(`albumTitle`) = ''
                      AND TRIM(`albumArtist`) = ''
                      AND `year` <= 0
                      AND (`artworkPath` IS NULL OR TRIM(`artworkPath`) = '')
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 to v4: sort keys, play statistics, and a stricter quarantine rule.
         *
         * Additive only — three columns with defaults, so no table is rebuilt
         * and playlists, favourites and album ids are untouched.
         *
         * Two backfills follow:
         *
         * 1. `addedAt` is seeded from `lastModified`. It is not the same thing —
         *    see Track.addedAt — but for rows that already exist it is the only
         *    evidence available, and leaving them all at 0 would collapse the
         *    date-added order into an arbitrary tie.
         *
         * 2. Quarantine is re-evaluated with the new artist-based rule from
         *    Track.looksUntagged. This deliberately moves files that are in the
         *    library today but carry no artist into "Review unconfirmed music",
         *    which is the point of the change: the old rule kept anything with a
         *    stray year or a piece of folder artwork, so recordings and voice
         *    notes accumulated in the library. Nothing is deleted, and anything
         *    the user wants back is two taps away.
         *
         * The predicate is kept in SQL rather than loading every row into
         * memory. It mirrors Track.looksUntagged, and the unit tests assert the
         * two agree across a matrix of inputs — including the literal
         * placeholder artist names, which is why LOWER and TRIM are both here.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `addedAt` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `playedMs` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `tracks` ADD COLUMN `isUserEdited` INTEGER NOT NULL DEFAULT 0"
                )

                db.execSQL("UPDATE `tracks` SET `addedAt` = `lastModified` WHERE `addedAt` = 0")

                db.execSQL(
                    """
                    UPDATE `tracks` SET `isUnconfirmed` = 1
                    WHERE TRIM(LOWER(`artist`)) IN
                            ('', '<unknown>', 'unknown', 'unknown artist',
                             'various', 'various artists')
                      AND TRIM(LOWER(`albumArtist`)) IN
                            ('', '<unknown>', 'unknown', 'unknown artist',
                             'various', 'various artists')
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: LibraryDatabase? = null

        /**
         * Get the process-wide database instance.
         *
         * There is deliberately no destructive fallback. Tracks are a
         * rebuildable cache, but playlists and favourites are not, so schema
         * changes must ship a migration rather than silently wiping user data.
         */
        fun getInstance(context: Context): LibraryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
