package com.bitperfect.android.library.dao

/**
 * Builders for SQL LIKE patterns.
 *
 * User text and file paths routinely contain `%` and `_`, which are LIKE
 * wildcards. Interpolating them unescaped makes a search for "50_50" match
 * far more than intended, so every pattern is escaped here and the queries
 * declare `ESCAPE '\'`.
 */
object SqlPatterns {

    private const val ESCAPE_CHARACTER = "\\"

    /**
     * Pattern matching anything containing [text].
     */
    fun contains(text: String): String = "%${escape(text)}%"

    /**
     * Pattern matching any path directly or indirectly inside [directory].
     *
     * The trailing separator matters: without it, `/Music` would also match
     * `/Musicals/...`.
     */
    fun directoryPrefix(directory: String): String =
        "${escape(directory.trimEnd('/'))}/%"

    /**
     * Escape LIKE wildcards, and the escape character itself first so it is
     * not doubled by the later replacements.
     */
    private fun escape(text: String): String = text
        .replace(ESCAPE_CHARACTER, ESCAPE_CHARACTER + ESCAPE_CHARACTER)
        .replace("%", "$ESCAPE_CHARACTER%")
        .replace("_", "${ESCAPE_CHARACTER}_")
}
