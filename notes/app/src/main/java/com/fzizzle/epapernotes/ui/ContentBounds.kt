package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.ink.Box

/**
 * Trim margins (decision 3): where a page's printed area is, so the screen can fit that to the
 * view instead of the whole sheet, and a slide's text comes out larger. Found on a small
 * picture of the page with its ink, so a note in the margin counts as printed. Screen only:
 * nothing here touches the PDF.
 */
object ContentBounds {
    /** A pixel with every channel at least this light is paper. */
    const val WHITE_ENOUGH = 245

    /** Kept round the printed area, so text never touches the screen's edge. */
    const val BORDER_PT = 12f

    // A row or column with fewer dark pixels than this is a speck (dust on a scan), not content.
    private const val SPECK = 2

    // A trim that keeps more of the page than this makes nothing larger worth the name.
    private const val MOST_OF_THE_PAGE = 0.9f

    /**
     * The smallest box, in pixels of the picture [pixels] (ARGB, [width] × [height]), that
     * holds every dark pixel of the rows and columns with at least [SPECK] of them. Null when
     * there are none: a blank page.
     */
    fun of(pixels: IntArray, width: Int, height: Int): Box? {
        val inRow = IntArray(height)
        val inColumn = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val dark = (p shr 16 and 0xff) < WHITE_ENOUGH || (p shr 8 and 0xff) < WHITE_ENOUGH || (p and 0xff) < WHITE_ENOUGH
                if (dark) {
                    inRow[y]++
                    inColumn[x]++
                }
            }
        }
        val top = inRow.indexOfFirst { it >= SPECK }
        val left = inColumn.indexOfFirst { it >= SPECK }
        if (top < 0 || left < 0) return null
        val bottom = inRow.indexOfLast { it >= SPECK }
        val right = inColumn.indexOfLast { it >= SPECK }
        return Box(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    /**
     * The box to fit the page to, in display points: the [content] found on a picture drawn at
     * [pxPerPt], with [BORDER_PT] round it, kept on the page. The whole page when nothing was
     * found, or when the box would keep most of it anyway (a picture printed edge to edge).
     */
    fun trim(pageSize: SizePt, content: Box?, pxPerPt: Float): Box {
        val whole = Box(0f, 0f, pageSize.w, pageSize.h)
        if (content == null) return whole
        val box = Box(
            (content.x0 / pxPerPt - BORDER_PT).coerceAtLeast(0f),
            (content.y0 / pxPerPt - BORDER_PT).coerceAtLeast(0f),
            (content.x1 / pxPerPt + BORDER_PT).coerceAtMost(pageSize.w),
            (content.y1 / pxPerPt + BORDER_PT).coerceAtMost(pageSize.h),
        )
        val kept = (box.x1 - box.x0) * (box.y1 - box.y0) / (pageSize.w * pageSize.h)
        return if (kept > MOST_OF_THE_PAGE) whole else box
    }
}
