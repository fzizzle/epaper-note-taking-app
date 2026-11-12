package com.fzizzle.epapernotes.ui

/**
 * Where things sit on the progress line under the writing strip ([ProgressRule]): the line's
 * width is shared out evenly between the document's pages, left to right. Plain arithmetic,
 * so it's tested on the laptop.
 */
object ProgressMarks {
    /** How far the line is black with [page] (from 0) on show: to the right edge of its share, and to the very end on the last page. */
    fun reachedPx(page: Int, pageCount: Int, widthPx: Int): Int {
        if (pageCount <= 0) return 0
        if (page >= pageCount - 1) return widthPx
        return ((page + 1).toLong() * widthPx / pageCount).toInt()
    }

    /** Where the tick for [page] sits: the middle of its share. */
    fun tickPx(page: Int, pageCount: Int, widthPx: Int): Int {
        if (pageCount <= 0) return 0
        return (((2L * page + 1) * widthPx) / (2L * pageCount)).toInt().coerceIn(0, (widthPx - 1).coerceAtLeast(0))
    }
}
