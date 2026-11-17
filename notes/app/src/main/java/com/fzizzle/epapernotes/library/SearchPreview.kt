package com.fzizzle.epapernotes.library

import java.util.Locale

/**
 * The preview under the search box: the matches, in a box a few rows tall that scrolls, shown
 * after a pause in typing, so the e-ink panel isn't redrawn at every key. Read from the index
 * only; the card is checked when Search is pressed.
 */
object SearchPreview {
    /** How many rows tall the box is; more matches scroll. */
    const val VISIBLE_ROWS = 5

    /** The most matches the box holds: as many as Search shows. */
    const val LIMIT = NameMatch.SEARCH_LIMIT

    /** The pause after the last key before the preview is asked for. */
    const val PAUSE_MS = 300L

    /** The fewest letters, all words together, worth previewing: one letter matches most of the library. */
    const val MIN_LETTERS = 2

    /** True when [query] has enough letters to preview (accents and spaces don't count). */
    fun wanted(query: String): Boolean = NameMatch.words(query).sumOf { it.length } >= MIN_LETTERS

    /** The line under the box: "Nothing found.", past [LIMIT] how many there are in all, or nothing. */
    fun line(wanted: Boolean, shown: Int, total: Int): String = when {
        !wanted -> ""
        total == 0 -> "Nothing found."
        total > shown -> "Showing ${grouped(shown)} of ${grouped(total)}. Type more to narrow it."
        else -> ""
    }

    /** 1234 → "1,234", whatever the tablet's language. */
    private fun grouped(n: Int): String = String.format(Locale.ROOT, "%,d", n)
}
