package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt

/**
 * The part of the page strip on screen: how far it's scrolled and how far it's zoomed.
 *
 *     screen pixel = content pixel × zoom − scroll
 *
 * At zoom 1 the pages exactly fill the width, so there's no sideways scrolling. Zooming in
 * (up to [MAX_ZOOM]) lets the strip be panned sideways too.
 */
class Viewport(private val layout: PageLayout, private val viewHeightPx: Int) {
    var scrollX = 0f
        private set
    var scrollY = 0f
        private set
    var zoom = 1f
        private set

    fun scrollBy(dx: Float, dy: Float) {
        scrollX += dx
        scrollY += dy
        clamp()
    }

    /** Scrolls so the top of [page] is at the top of the screen. */
    fun scrollToPage(page: Int) {
        scrollY = layout.pageTop(page) * zoom
        clamp()
    }

    /** Zooms by [factor], keeping the spot under the fingers ([focusX], [focusY]) where it is. */
    fun zoomAround(factor: Float, focusX: Float, focusY: Float) {
        val contentX = (focusX + scrollX) / zoom
        val contentY = (focusY + scrollY) / zoom
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        scrollX = contentX * zoom - focusX
        scrollY = contentY * zoom - focusY
        clamp()
    }

    /** Which page a screen pixel is on, and where on that page, in display points. */
    fun screenToPage(x: Float, y: Float): Pair<Int, Pt> {
        val contentX = (x + scrollX) / zoom
        val contentY = (y + scrollY) / zoom
        val page = layout.pageAt(contentY)
        val scale = layout.pageScale(page)
        return page to Pt(contentX / scale, (contentY - layout.pageTop(page)) / scale)
    }

    /** Display points on [page] to screen pixels: what drawing the page needs. */
    fun pageToScreen(page: Int): Affine {
        val s = layout.pageScale(page) * zoom
        return Affine.scale(s).then(Affine.translate(-scrollX, layout.pageTop(page) * zoom - scrollY))
    }

    /** Screen pixels per PDF point on [page], at the current zoom. The pen's width needs it. */
    fun pxPerPt(page: Int): Float = layout.pageScale(page) * zoom

    /** The page under the middle of the screen, for the "4 / 32" indicator. */
    fun currentPage(): Int = layout.pageAt((scrollY + viewHeightPx / 2f) / zoom)

    /** The pages at least partly on screen. */
    fun visiblePages(): IntRange = layout.visiblePages(scrollY / zoom, (scrollY + viewHeightPx) / zoom)

    private fun clamp() {
        val maxX = layout.viewWidthPx * zoom - layout.viewWidthPx
        val maxY = (layout.contentHeight * zoom - viewHeightPx).coerceAtLeast(0f)
        scrollX = scrollX.coerceIn(0f, maxX)
        scrollY = scrollY.coerceIn(0f, maxY)
    }

    companion object {
        const val MAX_ZOOM = 4f
    }
}
