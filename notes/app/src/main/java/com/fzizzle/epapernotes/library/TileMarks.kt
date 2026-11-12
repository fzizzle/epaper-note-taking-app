package com.fzizzle.epapernotes.library

/**
 * What a library tile shows besides its picture and name: how far into the PDF the owner is
 * ([progress], 0 to 1; null for no bar), whether it holds their writing, and its course's
 * colour (an ARGB colour; null for none).
 */
data class TileMarks(val progress: Float? = null, val hasInk: Boolean = false, val stripe: Int? = null) {
    companion object {
        val NONE = TileMarks()
    }
}

/**
 * How full a PDF's progress bar is: the page it was left on out of its pages, counting that
 * page as read. Null, no bar, for a file never [opened] or whose page count isn't known.
 */
fun progressOf(opened: Boolean, lastPage: Int, pageCount: Int): Float? {
    if (!opened || pageCount <= 0) return null
    return ((lastPage + 1).toFloat() / pageCount).coerceIn(0f, 1f)
}
