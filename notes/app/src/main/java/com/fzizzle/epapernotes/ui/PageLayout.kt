package com.fzizzle.epapernotes.ui

/** A page's size as shown (rotation applied), in PDF points. */
data class SizePt(val w: Float, val h: Float)

/**
 * Where each page sits in the long vertical strip of pages (spec §6.2), at zoom 1.
 *
 * Every page is scaled to fill the view's width, so a landscape slide gets more pixels per
 * point than a portrait page. Positions are in "content pixels": screen pixels before
 * zooming and scrolling.
 */
class PageLayout(private val pageSizes: List<SizePt>, val viewWidthPx: Int, private val gapPx: Int = 16) {

    private val tops = FloatArray(pageSizes.size)
    private val heights = FloatArray(pageSizes.size)

    init {
        require(pageSizes.isNotEmpty()) { "a document has at least one page" }
        var y = 0f
        for (i in pageSizes.indices) {
            tops[i] = y
            heights[i] = pageSizes[i].h * pageScale(i)
            y += heights[i] + gapPx
        }
    }

    val pageCount: Int get() = pageSizes.size

    val contentHeight: Float get() = tops.last() + heights.last()

    fun pageTop(i: Int): Float = tops[i]

    fun pageHeight(i: Int): Float = heights[i]

    /** Content pixels per PDF point for page [i], at zoom 1. */
    fun pageScale(i: Int): Float = viewWidthPx / pageSizes[i].w

    /** The page at a height in the strip. A point in a gap belongs to the page above it. */
    fun pageAt(contentY: Float): Int {
        for (i in 0 until pageCount - 1) {
            if (contentY < tops[i + 1]) return i
        }
        return pageCount - 1
    }

    fun visiblePages(top: Float, bottom: Float): IntRange = pageAt(top)..pageAt(bottom)
}
