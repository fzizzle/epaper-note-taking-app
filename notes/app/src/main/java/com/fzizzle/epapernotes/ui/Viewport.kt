package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box

/** A page's size as shown (rotation applied), in PDF points. */
data class SizePt(val w: Float, val h: Float)

/**
 * One page at a time (spec §6.2): which page is showing, how far it's zoomed, and where it
 * sits on screen.
 *
 * At normal size the whole page fits the screen, centred. Zoomed in (up to [MAX_ZOOM]) it
 * can be moved around, but never further than its own edges.
 *
 * With Trim margins, [fit] gives each page the box to fit instead, in display points (see
 * ContentBounds): that box is what fills the screen at normal size, what zoom counts from, and
 * whose edges limit moving. Everything else still speaks of the whole page: [pageToScreen]
 * maps whole-page display points, so the pen's mapping, the selection layer and
 * [visiblePagePart] work as without a box, and the whole page's picture is drawn, cut by the view.
 */
class Viewport(
    private val sizes: List<SizePt>,
    private val viewWidthPx: Int,
    private val viewHeightPx: Int,
    private val fit: List<Box>? = null,
) {

    var page = 0
        private set
    var zoom = 1f
        private set

    // Where the page's top-left corner is on screen, in pixels.
    private var x0 = 0f
    private var y0 = 0f

    init {
        require(sizes.isNotEmpty()) { "a document has at least one page" }
        require(fit == null || fit.size == sizes.size) { "one box to fit for each page" }
        goTo(0)
    }

    val pageCount: Int get() = sizes.size

    /** Shows [page] (kept within the document), whole (or its box, see [fit]) and centred. */
    fun goTo(page: Int) {
        this.page = page.coerceIn(0, sizes.size - 1)
        zoom = 1f
        x0 = 0f
        y0 = 0f
        clamp() // at normal size, clamping is centring
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
    fun pxPerPt(): Float = fittedPxPerPt(page) * zoom

    /** Screen pixels per PDF point for [page] at normal size, on show or not: how big to draw its picture. */
    fun fittedPxPerPt(page: Int): Float = scaleToFit(box(page), viewWidthPx, viewHeightPx)

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
     * edge to push past: it reports 0, or every up or down drag would turn the page. With a
     * box to fit ([fit]), all this is of the box's edges.
     */
    fun panBy(dx: Float, dy: Float): Float {
        x0 -= dx
        val wantedY = y0 - dy
        y0 = wantedY
        clamp()
        val b = box(page)
        return if ((b.y1 - b.y0) * pxPerPt() > viewHeightPx) y0 - wantedY else 0f
    }

    /**
     * Where the page is on screen, cut to the view: `[left, top, right, bottom]` in pixels. A
     * plain array, not Android's RectF, so it can be tested on the laptop. The whole page's,
     * even with a box to fit: the page round the box that is on screen can be written on too.
     */
    fun pageBoundsPx(): FloatArray = floatArrayOf(
        maxOf(x0, 0f),
        maxOf(y0, 0f),
        minOf(x0 + pageWidthPx(), viewWidthPx.toFloat()),
        minOf(y0 + pageHeightPx(), viewHeightPx.toFloat()),
    )

    /**
     * The part of the page on screen, in display points: the view cut to the page's edges.
     * Zoomed in, this is what is drawn again at its true size once the fingers lift (PageView's
     * sharp patch), so it is never bigger than the view in pixels.
     */
    fun visiblePagePart(): Box {
        val b = pageBoundsPx()
        val topLeft = screenToPage(b[0], b[1])
        val bottomRight = screenToPage(b[2], b[3])
        return Box(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    }

    /** Display points on the page to screen pixels: what drawing the page needs. */
    fun pageToScreen(): Affine = Affine.scale(pxPerPt()).then(Affine.translate(x0, y0))

    /** Where a screen pixel is on the page, in display points. It can be off the page's edges. */
    fun screenToPage(x: Float, y: Float): Pt = Pt((x - x0) / pxPerPt(), (y - y0) / pxPerPt())

    /** What fills the screen at normal size: [page]'s box to fit, or the whole page. */
    private fun box(page: Int): Box = fit?.get(page) ?: Box(0f, 0f, sizes[page].w, sizes[page].h)

    private fun pageWidthPx() = sizes[page].w * pxPerPt()

    private fun pageHeightPx() = sizes[page].h * pxPerPt()

    /**
     * The box (the whole page without [fit]) smaller than the screen in a direction: centred.
     * Bigger: its edges can't come inside the screen. Worked out on where the box is, then
     * turned back into where the page's corner is.
     */
    private fun clamp() {
        val b = box(page)
        val s = pxPerPt()
        x0 = clampAxis(x0 + b.x0 * s, (b.x1 - b.x0) * s, viewWidthPx.toFloat()) - b.x0 * s
        y0 = clampAxis(y0 + b.y0 * s, (b.y1 - b.y0) * s, viewHeightPx.toFloat()) - b.y0 * s
    }

    private fun clampAxis(start: Float, length: Float, view: Float): Float =
        if (length <= view) (view - length) / 2 else start.coerceIn(view - length, 0f)

    companion object {
        const val MAX_ZOOM = 4f

        /** Screen pixels per point that make [box] fill a [viewWidthPx] × [viewHeightPx] view, whole. */
        fun scaleToFit(box: Box, viewWidthPx: Int, viewHeightPx: Int): Float =
            minOf(viewWidthPx / (box.x1 - box.x0), viewHeightPx / (box.y1 - box.y0))
    }
}
