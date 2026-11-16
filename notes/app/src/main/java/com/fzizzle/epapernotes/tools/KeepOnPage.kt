package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.transformed

/**
 * Nothing is saved beside the page: the journal only holds what lies on a page, so a moved or
 * pasted item that stuck out past the edge would be cut off when saved (review B4).
 */
object KeepOnPage {
    /**
     * How far to move [box] (display points; the page runs from 0,0 to [pageWidth],[pageHeight])
     * so it lies on the page. In each direction on its own: a box that fits moves the least that
     * puts it inside; a box bigger than the page moves the least that makes it cover the page.
     */
    fun shift(box: Box, pageWidth: Float, pageHeight: Float): Pt =
        Pt(shiftOn(box.x0, box.x1, pageWidth), shiftOn(box.y0, box.y1, pageHeight))

    private fun shiftOn(low: Float, high: Float, size: Float): Float = when {
        high - low <= size -> when {
            low < 0f -> -low
            high > size -> size - high
            else -> 0f
        }
        low > 0f -> -low
        high < size -> size - high
        else -> 0f
    }
}

/**
 * [items] moved, all together, so their outline as shown ([pdfToDisplay]) lies on the page; the
 * same list when it already does.
 */
fun onPage(items: List<Item>, pdfToDisplay: Affine, pageWidth: Float, pageHeight: Float): List<Item> {
    if (items.isEmpty()) return items
    val corners = items.flatMap { listOf(Pt(it.box.x0, it.box.y0), Pt(it.box.x1, it.box.y1)) }.map { pdfToDisplay.map(it) }
    val outline = Box(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    val by = KeepOnPage.shift(outline, pageWidth, pageHeight)
    if (by.x == 0f && by.y == 0f) return items
    // The same move in PDF space: the display shift, carried back through the page's transform.
    val move = pdfToDisplay.then(Affine.translate(by.x, by.y)).then(pdfToDisplay.inverse())
    return items.map { it.transformed(move, 1f) }
}
