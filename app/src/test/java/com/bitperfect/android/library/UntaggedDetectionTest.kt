package com.bitperfect.android.library

import com.bitperfect.android.library.model.Track
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The rule that decides whether a file is quarantined out of the main library.
 *
 * A false positive hides real music from the user, which is much worse than a
 * stray recording showing up, so these tests lean on proving that anything with
 * a single identifying tag stays in the library.
 */
class UntaggedDetectionTest {

    private fun track(
        artist: String = "",
        albumTitle: String = "",
        albumArtist: String = "",
        year: Int = 0,
        artworkPath: String? = null,
        title: String = "some-file",
        duration: Long = 0
    ) = Track(
        path = "/storage/emulated/0/x/$title.m4a",
        title = title,
        artist = artist,
        albumTitle = albumTitle,
        albumArtist = albumArtist,
        year = year,
        artworkPath = artworkPath,
        duration = duration
    )

    // --- Quarantined ---

    @Test
    @DisplayName("a file with no tags at all is quarantined")
    fun noTagsAtAll() {
        assertTrue(Track.looksUntagged(track()))
    }

    @Test
    @DisplayName("whitespace-only tags count as absent")
    fun whitespaceTags() {
        assertTrue(
            Track.looksUntagged(
                track(artist = "   ", albumTitle = "\t", albumArtist = " ", artworkPath = "  ")
            )
        )
    }

    @Test
    @DisplayName("a negative or zero year counts as absent")
    fun zeroYear() {
        assertTrue(Track.looksUntagged(track(year = 0)))
        assertTrue(Track.looksUntagged(track(year = -1)))
    }

    // --- Kept in the library: one tag is enough ---

    @Test
    @DisplayName("an artist alone keeps a track in the library")
    fun artistOnly() {
        assertFalse(Track.looksUntagged(track(artist = "Nils Frahm")))
    }

    @Test
    @DisplayName("an album alone keeps a track in the library")
    fun albumOnly() {
        assertFalse(Track.looksUntagged(track(albumTitle = "Spaces")))
    }

    @Test
    @DisplayName("an album artist alone keeps a track in the library")
    fun albumArtistOnly() {
        assertFalse(Track.looksUntagged(track(albumArtist = "Various Artists")))
    }

    @Test
    @DisplayName("a year alone keeps a track in the library")
    fun yearOnly() {
        assertFalse(Track.looksUntagged(track(year = 2013)))
    }

    @Test
    @DisplayName("artwork alone keeps a track in the library")
    fun artworkOnly() {
        assertFalse(Track.looksUntagged(track(artworkPath = "content://media/12/albumart")))
    }

    @Test
    @DisplayName("the title is not evidence, because the scanner falls back to the file name")
    fun titleIsNotEvidence() {
        // Every scanned file has a title, so treating it as a tag would mean
        // nothing was ever quarantined.
        assertTrue(Track.looksUntagged(track(title = "Recording 004")))
    }

    @Test
    @DisplayName("a long duration is not evidence either")
    fun durationIsNotEvidence() {
        // A 40-minute untagged file is as likely to be a lecture as an album.
        assertTrue(Track.looksUntagged(track(duration = 40 * 60 * 1000L)))
    }

    // --- The migration SQL must agree with the Kotlin ---

    /**
     * MIGRATION_2_3 backfills existing rows with a WHERE clause that mirrors
     * [Track.looksUntagged]. The two live in different languages and can drift,
     * so this reimplements the SQL predicate and asserts they agree across a
     * matrix of inputs.
     *
     * If this fails, the migration and the scanner disagree, which shows up as
     * tracks that vanish or reappear after an upgrade.
     */
    @Test
    @DisplayName("migration SQL predicate matches Track.looksUntagged")
    fun migrationSqlMatchesKotlin() {
        // TRIM(x) = '' in SQL, and NULL-or-blank for artworkPath.
        fun sqlPredicate(
            artist: String,
            albumTitle: String,
            albumArtist: String,
            year: Int,
            artworkPath: String?
        ): Boolean =
            artist.trim() == "" &&
                albumTitle.trim() == "" &&
                albumArtist.trim() == "" &&
                year <= 0 &&
                (artworkPath == null || artworkPath.trim() == "")

        val strings = listOf("", " ", "\t", "Nils Frahm")
        val years = listOf(-1, 0, 1999)
        val artworks = listOf(null, "", "   ", "content://media/1/albumart")

        var checked = 0
        for (artist in strings) {
            for (album in strings) {
                for (albumArtist in strings) {
                    for (year in years) {
                        for (artwork in artworks) {
                            val kotlin = Track.looksUntagged(
                                artist = artist,
                                albumTitle = album,
                                albumArtist = albumArtist,
                                year = year,
                                artworkPath = artwork
                            )
                            val sql = sqlPredicate(artist, album, albumArtist, year, artwork)
                            assertTrue(
                                kotlin == sql,
                                "disagreement for artist='$artist' album='$album' " +
                                    "albumArtist='$albumArtist' year=$year artwork=$artwork: " +
                                    "kotlin=$kotlin sql=$sql"
                            )
                            checked++
                        }
                    }
                }
            }
        }
        // Guard against the loops silently collapsing to nothing.
        assertTrue(checked == 4 * 4 * 4 * 3 * 4, "expected full matrix, checked $checked")
    }
}
