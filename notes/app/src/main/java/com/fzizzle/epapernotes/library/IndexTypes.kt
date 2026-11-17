package com.fzizzle.epapernotes.library

/**
 * One PDF found by a search. [folder] is the path from the library folder with `/` between
 * names ("" for the library folder itself); [fileName] includes ".pdf".
 */
data class Hit(val folder: String, val fileName: String)

/** What a search found: how many PDFs matched in all, and the first few of them in display order. */
data class Matches(val total: Int, val hits: List<Hit>)
