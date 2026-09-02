package com.bitperfect.android.library

import com.bitperfect.android.library.model.Track
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The rule that decides whether a file is quarantined out of the main library.
 *
 * The rule is the **artist**: a file that does not say who made it is a voice
 * note, a ringtone or a recording far more often than it is music. Music that
 * someone deliberately put on their phone essentially always carries an artist.
 *
 * This is stricter than the original version, which required every tag to be
 * absent and therefore admitted anything carrying a stray year or a scrap of
 * folder artwork. Recordings kept reaching the library, so `albumTitle`, `year`
 * and artwork are no longer treated as evidence — the tests below pin that down,
 * because it is the part most likely to be "helpfully" relaxed again later.
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
    @DisplayName("whitespace-only artist tags count as absent")
    fun whitespaceTags() {
        assertTrue(Track.looksUntagged(track(artist = "   ", albumArtist = "\t")))
    }

    @Test
    @DisplayName("placeholder artist names count as absent, whatever their case")
    fun placeholderArtistNames() {
        // What a ripper writes when it could not identify the disc, and what
        // MediaStore itself uses. Treated as text, every one of these would show
        // up in the library under an artist called "Unknown Artist".
        for (placeholder in listOf(
            "<unknown>",
            "unknown",
            "Unknown",
            "UNKNOWN",
            "Unknown Artist",
            "unknown artist",
            "  Unknown Artist  ",
            "Various",
            "various artists"
        )) {
            assertTrue(
                Track.looksUntagged(track(artist = placeholder, albumArtist = placeholder)),
                "expected '$placeholder' to count as no artist"
            )
        }
    }

    @Test
    @DisplayName("an album with no artist is quarantined")
    fun albumWithoutArtistIsQuarantined() {
        // The behaviour change. Under the old rule an album title alone kept a
        // file in the library, which is how untagged recordings sitting in a
        // named folder got in.
        assertTrue(Track.looksUntagged(track(albumTitle = "Spaces")))
    }

    @Test
    @DisplayName("a year with no artist is quarantined")
    fun yearWithoutArtistIsQuarantined() {
        assertTrue(Track.looksUntagged(track(year = 2013)))
    }

    @Test
    @DisplayName("artwork with no artist is quarantined")
    fun artworkWithoutArtistIsQuarantined() {
        // MediaStore will attach a folder image to anything sitting beside it, so
        // artwork says nothing about whether a file is music.
        assertTrue(Track.looksUntagged(track(artworkPath = "content://media/12/albumart")))
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

    // --- Kept in the library ---

    @Test
    @DisplayName("an artist keeps a track in the library")
    fun artistKeepsTrack() {
        assertFalse(Track.looksUntagged(track(artist = "Nils Frahm")))
    }

    @Test
    @DisplayName("an album artist alone keeps a track in the library")
    fun albumArtistAloneKeepsTrack() {
        // A compilation track often names only the album artist.
        assertFalse(Track.looksUntagged(track(albumArtist = "Ludovico Einaudi")))
    }

    @Test
    @DisplayName("a real artist whose name contains a placeholder word is kept")
    fun placeholderMatchingIsExact() {
        // The check is whole-value, not substring. Getting this wrong would hide
        // real bands.
        for (name in listOf(
            "Unknown Mortal Orchestra",
            "The Unknown",
            "Unknown Artist Collective",
            "Various Production"
        )) {
            assertFalse(
                Track.looksUntagged(track(artist = name)),
                "expected '$name' to count as a real artist"
            )
        }
    }

    @Test
    @DisplayName("a placeholder track artist is rescued by a real album artist")
    fun realAlbumArtistRescuesPlaceholderArtist() {
        assertFalse(
            Track.looksUntagged(track(artist = "Unknown Artist", albumArtist = "Hania Rani"))
        )
    }

    // --- The migration SQL must agree with the Kotlin ---

    /**
     * MIGRATION_3_4 re-applies quarantine with a WHERE clause that mirrors
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
        // TRIM(LOWER(x)) IN (...) in SQL. SQLite's LOWER is ASCII-only, which is
        // all these placeholder values need.
        val sqlPlaceholders = setOf(
            "", "<unknown>", "unknown", "unknown artist", "various", "various artists"
        )

        fun sqlPredicate(artist: String, albumArtist: String): Boolean =
            artist.lowercase().trim() in sqlPlaceholders &&
                albumArtist.lowercase().trim() in sqlPlaceholders

        val values = listOf(
            "",
            " ",
            "\t",
            "Nils Frahm",
            "<unknown>",
            "unknown",
            "Unknown",
            "Unknown Artist",
            "  unknown artist ",
            "Unknown Mortal Orchestra",
            "Various",
            "Various Artists",
            "Various Production"
        )

        var checked = 0
        for (artist in values) {
            for (albumArtist in values) {
                val kotlin = Track.looksUntagged(artist = artist, albumArtist = albumArtist)
                val sql = sqlPredicate(artist, albumArtist)
                assertTrue(
                    kotlin == sql,
                    "disagreement for artist='$artist' albumArtist='$albumArtist': " +
                        "kotlin=$kotlin sql=$sql"
                )
                checked++
            }
        }
        // Guard against the loops silently collapsing to nothing.
        assertTrue(
            checked == values.size * values.size,
            "expected full matrix, checked $checked"
        )
    }
}
