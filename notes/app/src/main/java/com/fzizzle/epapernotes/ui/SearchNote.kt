package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.library.NameMatch
import com.fzizzle.epapernotes.ui.NameIndex.Note
import java.util.Locale

/** The words of the line above search results (design §6). */
internal object SearchNote {
    /**
     * Why a second answer is coming, if one is, then, when more than [NameMatch.SEARCH_LIMIT]
     * matched, that only [shown] of the [total] are on screen. Empty when there is nothing to say.
     */
    fun text(note: Note, shown: Int, total: Int): String {
        val coming = when (note) {
            Note.NONE -> ""
            Note.CHECKING -> "Checking for changes…"
            Note.STILL_READING -> "Still reading the library…"
        }
        val more = if (total > NameMatch.SEARCH_LIMIT) "Showing ${grouped(shown)} of ${grouped(total)}. Type more to narrow it." else ""
        return listOf(coming, more).filter { it.isNotEmpty() }.joinToString("   ")
    }

    /** 1234 → "1,234", whatever the tablet's language. */
    private fun grouped(n: Int): String = String.format(Locale.ROOT, "%,d", n)
}
