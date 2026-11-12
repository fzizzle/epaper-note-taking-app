package com.fzizzle.epapernotes.ui

/**
 * The number being typed on the "Go to page" pad ([showPagePad]), for a document of
 * [pageCount] pages. Kept apart from the pad's buttons so its rules are tested on the laptop.
 */
class PageEntry(private val pageCount: Int) {
    /** The digits typed so far: never more than the page count has, and never starting with 0. */
    var text = ""
        private set

    private val maxDigits = pageCount.coerceAtLeast(1).toString().length

    fun press(digit: Int) {
        if (digit !in 0..9 || text.length >= maxDigits) return
        if (text.isEmpty() && digit == 0) return
        text += digit
    }

    /** ⌫: the last digit goes. */
    fun back() {
        text = text.dropLast(1)
    }

    /** The page to go to, from 0, kept within the document: a number past the end is the last page. Null when nothing is typed. */
    fun page(): Int? = text.toIntOrNull()?.let { it.coerceIn(1, pageCount.coerceAtLeast(1)) - 1 }
}
