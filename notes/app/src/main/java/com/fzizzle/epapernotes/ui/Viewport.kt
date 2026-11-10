package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt

/** A page's size as shown (rotation applied), in PDF points. */
data class SizePt(val w: Float, val h: Float)

/**
 * One page at a time (spec §6.2): which page is showing, how far it's zoomed, and where it
 * sits on screen.
 *
 * At normal size the whole page fits the screen, centred. Zoomed in (up to [MAX_ZOOM]) it
 * can be moved around, but never further than its own edges.
 */
class Viewport(private val sizes: List<SizePt>, private val viewWidthPx: Int, private val viewHeightPx: Int) {

    var page = 0
        private set
    var zoom = 1f
        private set

    // Where the page's top-left corner is on screen, in pixels.
    private var x0 = 0f
    private var y0 = 0f

    init {
        require(sizes.isNotEmpty()) { "a document has at least one page" }
        goTo(0)
    }

    val pageCount: Int get() = sizes.size

    /** Shows [page] (kept within the document), whole and centred. */
    fun goTo(page: Int) {
        this.page = page.coerceIn(0, sizes.size - 1)
        zoom = 1f
        x0 = (viewWidthPx - pageWidthPx()) / 2
        y0 = (viewHeightPx - pageHeightPx()) / 2
    }

    /** Returns false at the last page. */
    fun next(): Boolean {
        if (page >= sizes.size - 1) return false
        goTo(page + 1)
        return true
    }

    /** Returns false at the first page. */
    fun previous(): Boolean {
        if (page <= 0) return false
        goTo(page - 1)
        return true
    }

    /** Screen pixels per PDF point, at the current zoom. */
    fun pxPerPt(): Float = fitScale() * zoom

    /** Zooms by [factor], keeping the spot under the fingers ([focusX], [focusY]) where it is. */
    fun zoomAround(factor: Float, focusX: Float, focusY: Float) {
        val onPage = screenToPage(focusX, focusY)
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        x0 = focusX - onPage.x * pxPerPt()
        y0 = focusY - onPage.y * pxPerPt()
        clamp()
    }

    /**
     * Moves the view the way a finger drag does: positive [dy] goes further down the page.
     * Returns how far the vertical move went past the page's edge (positive past the
     * bottom, negative past the top). The screen uses that to turn the page.
     *
     * A page shorter than the screen (a zoomed landscape slide, say) stays centred and has no
     * edge to push past: it reports 0, or every up or down drag would turn the page.
     */
    fun panBy(dx: Float, dy: Float): Float {
        x0 -= dx
        val wantedY = y0 - dy
        y0 = wantedY
        clamp()
        return if (pageHeightPx() > viewHeightPx) y0 - wantedY else 0f
    }

    /**
     * Where the page is on screen, cut to the view: `[left, top, right, bottom]` in pixels. A
     * plain array, not Android's RectF, so it can be tested on the laptop.
     */
    fun pageBoundsPx(): FloatArray = floatArrayOf(
        maxOf(x0, 0f),
        maxOf(y0, 0f),
        minOf(x0 + pageWidthPx(), viewWidthPx.toFloat()),
        minOf(y0 + pageHeightPx(), viewHeightPx.toFloat()),
    )

    /** Display points on the page to screen pixels: what drawing the page needs. */
    fun pageToScreen(): Affine = Affine.scale(pxPerPt()).then(Affine.translate(x0, y0))

    /** Where a screen pixel is on the page, in display points. It can be off the page's edges. */
    fun screenToPage(x: Float, y: Float): Pt = Pt((x - x0) / pxPerPt(), (y - y0) / pxPerPt())

    private fun fitScale(): Float = minOf(viewWidthPx / sizes[page].w, viewHeightPx / sizes[page].h)

    private fun pageWidthPx() = sizes[page].w * pxPerPt()

    private fun pageHeightPx() = sizes[page].h * pxPerPt()

    /** Smaller than the screen in a direction: centred. Bigger: its edges can't come inside the screen. */
    private fun clamp() {
        x0 = clampAxis(x0, pageWidthPx(), viewWidthPx.toFloat())
        y0 = clampAxis(y0, pageHeightPx(), viewHeightPx.toFloat())
    }

    private fun clampAxis(start: Float, length: Float, view: Float): Float =
        if (length <= view) (view - length) / 2 else start.coerceIn(view - length, 0f)

    companion object {
        const val MAX_ZOOM = 4f
    }
}
