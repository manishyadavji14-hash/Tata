package com.bitperfect.android.library.dao

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for LIKE pattern escaping.
 *
 * The escape ordering here is easy to get wrong: escaping the backslash after
 * the wildcards would double the backslashes the wildcard escaping just added.
 * These tests also emulate SQLite's LIKE to confirm the patterns actually match
 * what they should, not merely that the strings look right.
 */
@DisplayName("SqlPatterns")
class SqlPatternsTest {

    @Test
    @DisplayName("Plain text is wrapped in wildcards untouched")
    fun plainContains() {
        assertEquals("%rock%", SqlPatterns.contains("rock"))
    }

    @Test
    @DisplayName("Percent in the search text is escaped")
    fun escapesPercent() {
        assertEquals("%50\\%%", SqlPatterns.contains("50%"))
    }

    @Test
    @DisplayName("Underscore in the search text is escaped")
    fun escapesUnderscore() {
        assertEquals("%50\\_50%", SqlPatterns.contains("50_50"))
    }

    @Test
    @DisplayName("Backslash is escaped exactly once, not doubled by later passes")
    fun escapesBackslashOnce() {
        // If the backslash were escaped after the wildcards, the backslash
        // added for `%` would itself be doubled and the pattern would look for
        // a literal backslash.
        assertEquals("%a\\\\b%", SqlPatterns.contains("a\\b"))
        assertEquals("%a\\\\\\%b%", SqlPatterns.contains("a\\%b"))
    }

    @Test
    @DisplayName("Directory prefix appends a separator before the wildcard")
    fun directoryPrefixAddsSeparator() {
        assertEquals("/Music/%", SqlPatterns.directoryPrefix("/Music"))
    }

    @Test
    @DisplayName("A trailing slash is not duplicated")
    fun directoryPrefixTrimsTrailingSlash() {
        assertEquals("/Music/%", SqlPatterns.directoryPrefix("/Music/"))
    }

    @Test
    @DisplayName("A folder prefix does not match a sibling with the same start")
    fun directoryPrefixDoesNotMatchSibling() {
        val pattern = SqlPatterns.directoryPrefix("/Music")

        assertTrue(likeMatches("/Music/a.flac", pattern))
        assertTrue(likeMatches("/Music/Rock/b.flac", pattern))
        // The bug the trailing separator prevents.
        assertFalse(likeMatches("/Musicals/c.flac", pattern))
        // The directory itself is not one of its own children.
        assertFalse(likeMatches("/Music", pattern))
    }

    @Test
    @DisplayName("An underscore in a folder name is literal, not a wildcard")
    fun underscoreInFolderIsLiteral() {
        val pattern = SqlPatterns.directoryPrefix("/Music/Rock_80s")

        assertTrue(likeMatches("/Music/Rock_80s/a.flac", pattern))
        // Unescaped, `_` would match any single character.
        assertFalse(likeMatches("/Music/Rock80s/a.flac", pattern))
        assertFalse(likeMatches("/Music/RockX80s/a.flac", pattern))
    }

    @Test
    @DisplayName("A percent in the search text does not match everything")
    fun percentDoesNotMatchEverything() {
        val pattern = SqlPatterns.contains("100%")

        assertTrue(likeMatches("Live 100% Acoustic", pattern))
        assertFalse(likeMatches("Live 100 Acoustic", pattern))
    }

    /**
     * Minimal SQLite LIKE evaluator for `%`, `_` and `ESCAPE '\'`.
     *
     * Translating the pattern to a regex lets these tests assert on matching
     * behaviour rather than on the exact escaped string, which is what actually
     * matters at the database.
     */
    private fun likeMatches(value: String, pattern: String): Boolean {
        val regex = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    regex.append(Regex.escape(pattern[i + 1].toString()))
                    i += 2
                }
                c == '%' -> {
                    regex.append("[\\s\\S]*")
                    i++
                }
                c == '_' -> {
                    regex.append("[\\s\\S]")
                    i++
                }
                else -> {
                    regex.append(Regex.escape(c.toString()))
                    i++
                }
            }
        }
        return Regex(regex.toString()).matches(value)
    }
}
