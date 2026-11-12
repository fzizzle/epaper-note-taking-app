package com.fzizzle.epapernotes.ui

/** How many copies explain how to paste before the message is just "Copied." (UI review, decision 6). */
const val COPY_HINTS = 3

/** What a copy or snip says, given how many times the full hint has been shown before. */
fun copyMessage(timesShown: Int): String =
    if (timesShown < COPY_HINTS) "Copied. To paste, hold a finger on the page, or tap Paste." else "Copied."
