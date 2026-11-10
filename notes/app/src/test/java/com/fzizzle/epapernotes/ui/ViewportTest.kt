package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportTest {
    private val portrait = SizePt(612f, 792f)
    private val landscape = SizePt(792f, 612f)
    private fun viewport() = Viewport(listOf(portrait, landscape, portrait), viewWidthPx = 1860, viewHeightPx = 2300)

    private fun corners(v: Viewport, size: SizePt): Pair<Pt, Pt> {
        val m = v.pageToScreen()
        return m.map(Pt(0f, 0f)) to m.map(Pt(size.w, size.h))
    }

    @Test fun portraitPageFitsTheScreen() {
        val (tl, br) = corners(viewport(), portrait)
        val scale = minOf(1860f / 612, 2300f / 792) // whole page visible
        assertEquals(scale, viewport().pxPerPt(), 1e-4f)
        assertEquals((1860 - 612 * scale) / 2, tl.x, 0.5f) // centred
        assertEquals(0f, tl.y, 0.5f)
        assertEquals(2300f, br.y, 0.5f)
    }

    @Test fun landscapePageIsCentredVertically() {
        val v = viewport()
        v.next()
        val (tl, br) = corners(v, landscape)
        assertEquals(0f, tl.x, 0.5f)
        assertEquals(1860f, br.x, 0.5f)
        assertEquals(2300f / 2, (tl.y + br.y) / 2, 0.5f)
    }

    @Test fun nextAndPreviousStayInRange() {
        val v = viewport()
        assertFalse(v.previous())
        assertTrue(v.next())
        assertTrue(v.next())
        assertFalse(v.next())
        assertEquals(2, v.page)
    }

    @Test fun turningThePageResetsZoom() {
        val v = viewport()
        v.zoomAround(3f, 900f, 900f)
        v.next()
        assertEquals(1f, v.zoom, 1e-4f)
    }

    @Test fun noPanningAtNormalSize() {
        val v = viewport()
        val before = v.pageToScreen()
        v.panBy(300f, 300f)
        assertEquals(before, v.pageToScreen())
    }

    @Test fun zoomKeepsFocusPointStill() {
        val v = viewport()
        val before = v.screenToPage(600f, 900f)
        v.zoomAround(2f, 600f, 900f)
        val after = v.screenToPage(600f, 900f)
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }

    @Test fun zoomIsClampedTo1And4() {
        val v = viewport()
        v.zoomAround(10f, 0f, 0f)
        assertEquals(4f, v.zoom, 1e-4f)
        v.zoomAround(0.01f, 0f, 0f)
        assertEquals(1f, v.zoom, 1e-4f)
    }

    @Test fun panIsClampedToThePage() {
        val v = viewport()
        v.zoomAround(2f, 0f, 0f)
        v.panBy(-1e6f, -1e6f) // try to drag far past the top-left
        val tl = v.pageToScreen().map(Pt(0f, 0f))
        assertEquals(0f, tl.x, 0.5f)
        assertEquals(0f, tl.y, 0.5f)
    }

    /** Pushing past the page's edge is reported, so the screen can turn the page. */
    @Test fun panReportsHowFarItWentPastTheEdge() {
        val v = viewport()
        v.zoomAround(2f, 0f, 0f)
        assertEquals(0f, v.panBy(0f, 100f), 0.01f) // still room to move down the page
        v.panBy(0f, 1e5f) // to the bottom
        assertEquals(250f, v.panBy(0f, 250f), 0.01f) // past the bottom edge
        assertEquals(0f, v.panBy(0f, -50f), 0.01f) // back up: fine
    }

    /**
     * Review 2.5: a zoomed landscape slide that is still shorter than the screen has no top or
     * bottom edge to push past. Any up or down drag used to count as "past the edge" and turn the page.
     */
    @Test fun shortZoomedPageNeverReportsPastTheEdge() {
        val v = Viewport(listOf(SizePt(960f, 540f)), viewWidthPx = 1860, viewHeightPx = 2300)
        v.zoomAround(1.5f, 930f, 1150f)
        assertEquals(0f, v.panBy(0f, 400f), 0.01f)
        assertEquals(0f, v.panBy(0f, -400f), 0.01f)
    }

    @Test fun tallZoomedPageStillReportsPastTheEdge() {
        val v = viewport()
        v.zoomAround(2f, 930f, 1150f)
        v.panBy(0f, -1e5f) // to the top
        assertEquals(-300f, v.panBy(0f, -300f), 0.01f)
        v.panBy(0f, 1e5f) // to the bottom
        assertEquals(300f, v.panBy(0f, 300f), 0.01f)
    }

    /** The part of the page that's on screen: where the pen may write (review 2.6). */
    @Test fun pageBoundsAreClippedToTheView() {
        // At normal size the portrait page fills the height and leaves a margin on each side.
        val v = viewport()
        val pageWidth = 612 * (2300f / 792)
        assertArrayEquals(floatArrayOf((1860 - pageWidth) / 2, 0f, (1860 + pageWidth) / 2, 2300f), v.pageBoundsPx(), 0.5f)

        // Zoomed in, it's bigger than the screen both ways: the whole view, however it's moved.
        v.zoomAround(2f, 930f, 1150f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1860f, 2300f), v.pageBoundsPx(), 0.01f)
        v.panBy(200f, -300f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1860f, 2300f), v.pageBoundsPx(), 0.01f)

        // A zoomed slide: wider than the screen, but still shorter, so there's room above and below.
        val slide = Viewport(listOf(SizePt(960f, 540f)), viewWidthPx = 1860, viewHeightPx = 2300)
        slide.zoomAround(1.5f, 930f, 1150f)
        val slideHeight = 540 * (1860f / 960) * 1.5f
        assertArrayEquals(floatArrayOf(0f, (2300 - slideHeight) / 2, 1860f, (2300 + slideHeight) / 2), slide.pageBoundsPx(), 0.5f)
    }

    private fun assertBox(expected: Box, actual: Box) {
        assertArrayEquals(floatArrayOf(expected.x0, expected.y0, expected.x1, expected.y1), floatArrayOf(actual.x0, actual.y0, actual.x1, actual.y1), 0.01f)
    }

    /** The sharp patch drawn over a zoomed page is this part of it, so it must be what's on screen. */
    @Test fun visiblePagePartMatchesTheScreen() {
        val v = Viewport(listOf(SizePt(600f, 800f)), viewWidthPx = 1200, viewHeightPx = 1600)
        v.zoomAround(2f, 600f, 800f)
        assertBox(Box(150f, 200f, 450f, 600f), v.visiblePagePart()) // the middle 300 × 400 pt
    }

    /** A zoomed slide still shorter than the screen: the part stops at the page's top and bottom. */
    @Test fun visiblePagePartIsClippedToThePage() {
        val v = Viewport(listOf(SizePt(960f, 540f)), viewWidthPx = 1860, viewHeightPx = 2300)
        v.zoomAround(1.5f, 930f, 1150f)
        val pxPerPt = (1860f / 960) * 1.5f
        val across = 1860 / pxPerPt // as wide as the screen, centred
        assertBox(Box((960 - across) / 2, 0f, (960 + across) / 2, 540f), v.visiblePagePart())
    }

    @Test fun atNormalSizeThePartIsTheWholePage() {
        assertBox(Box(0f, 0f, 612f, 792f), viewport().visiblePagePart())
    }

    @Test fun goToShowsThatPage() {
        val v = viewport()
        v.goTo(2)
        assertEquals(2, v.page)
        v.goTo(99)
        assertEquals(2, v.page)
    }

    // Trim margins: a 600 × 800 pt page whose printed area is 100..500 × 100..700 pt.
    private val sheet = SizePt(600f, 800f)
    private val printed = Box(100f, 100f, 500f, 700f)
    private fun trimmed() = Viewport(listOf(sheet), viewWidthPx = 1200, viewHeightPx = 1800, fit = listOf(printed))

    @Test fun aTrimmedPageFillsTheViewWithItsContent() {
        val v = trimmed()
        assertEquals(3f, v.pxPerPt(), 1e-4f) // 400 × 600 pt in 1200 × 1800 px
        val tl = v.screenToPage(0f, 0f)
        assertEquals(100f, tl.x, 0.5f)
        assertEquals(100f, tl.y, 0.5f)
        val br = v.screenToPage(1200f, 1800f)
        assertEquals(500f, br.x, 0.5f)
        assertEquals(700f, br.y, 0.5f)
        // What's on show is the printed area: the sharp patch, zoomed, is cut from this.
        assertBox(printed, v.visiblePagePart())
    }

    @Test fun aTrimmedPageCantBeMovedOutsideItsBox() {
        val v = trimmed()
        v.panBy(300f, 300f) // at normal size it stays put
        assertEquals(100f, v.screenToPage(0f, 0f).x, 0.5f)
        v.zoomAround(10f, 600f, 900f)
        assertEquals(4f, v.zoom, 1e-4f)
        assertEquals(12f, v.pxPerPt(), 1e-4f) // zoom counts from the box filling the view
        v.panBy(-1e6f, -1e6f)
        val tl = v.screenToPage(0f, 0f)
        assertEquals(100f, tl.x, 0.5f)
        assertEquals(100f, tl.y, 0.5f)
        v.panBy(1e6f, 1e6f)
        val br = v.screenToPage(1200f, 1800f)
        assertEquals(500f, br.x, 0.5f)
        assertEquals(700f, br.y, 0.5f)
        // Pushed on past the box's bottom is past the edge: that turns the page.
        assertEquals(250f, v.panBy(0f, 250f), 0.01f)
    }

    /**
     * A wide printed area: it fills the width, and the page above and below it shows too. The
     * page on screen is never more than the view, so the pen's limit stays on the screen.
     */
    @Test fun aTrimmedPagesBoundsStayInTheView() {
        val v = Viewport(listOf(sheet), viewWidthPx = 1200, viewHeightPx = 1800, fit = listOf(Box(100f, 300f, 500f, 500f)))
        assertEquals(3f, v.pxPerPt(), 1e-4f)
        assertEquals(400f, v.screenToPage(0f, 900f).y, 0.5f) // the box's middle is the view's
        assertArrayEquals(floatArrayOf(0f, 0f, 1200f, 1800f), v.pageBoundsPx(), 0.01f)
        assertBox(Box(100f, 100f, 500f, 700f), v.visiblePagePart())
    }

    /** Each page is fitted to its own box, also the ones not on show (their pictures are made ahead). */
    @Test fun eachPageIsFittedToItsOwnBox() {
        val v = Viewport(listOf(sheet, sheet), viewWidthPx = 1200, viewHeightPx = 1800, fit = listOf(printed, Box(0f, 0f, 600f, 800f)))
        assertEquals(3f, v.fittedPxPerPt(0), 1e-4f)
        assertEquals(2f, v.fittedPxPerPt(1), 1e-4f)
        v.zoomAround(2f, 600f, 900f)
        assertEquals(3f, v.fittedPxPerPt(0), 1e-4f) // not the zoom
        v.next()
        assertEquals(2f, v.pxPerPt(), 1e-4f)
        assertEquals(0f, v.screenToPage(0f, 100f).x, 0.5f) // the whole page, as without a box
    }

    /** A box that is the whole page is the same as no box, zoomed and moved or not. */
    @Test fun withoutAFitBoxNothingChanges() {
        val sizes = listOf(portrait, landscape)
        val plain = Viewport(sizes, viewWidthPx = 1860, viewHeightPx = 2300)
        val whole = Viewport(sizes, viewWidthPx = 1860, viewHeightPx = 2300, fit = sizes.map { Box(0f, 0f, it.w, it.h) })
        listOf(plain, whole).forEach {
            it.zoomAround(2.5f, 400f, 700f)
            it.panBy(120f, -60f)
        }
        assertEquals(plain.pageToScreen(), whole.pageToScreen())
        assertEquals(plain.panBy(0f, 1e5f), whole.panBy(0f, 1e5f), 0.01f)
        plain.next()
        whole.next()
        assertEquals(plain.pageToScreen(), whole.pageToScreen())
    }

    /** The whole chain: PDF point → display point → screen pixel, and back, zoomed, panned and rotated. */
    @Test fun mapsThroughZoomPanAndRotation() {
        // Page 1 is a portrait page with MediaBox (100,100)-(712,892), turned 90°. This is the
        // transform PdfFile.pdfToDisplay gives for it (checked on the tablet in Task 2).
        val pdfToDisplay = Affine(a = 0f, b = 1f, c = 1f, d = 0f, e = -100f, f = -100f)
        val v = viewport()
        v.next()
        v.zoomAround(2f, 900f, 1200f)
        v.panBy(150f, -80f)

        val screen = v.pageToScreen().map(pdfToDisplay.map(Pt(150f, 842f)))
        val back = pdfToDisplay.inverse().map(v.screenToPage(screen.x, screen.y))
        assertEquals(150f, back.x, 0.5f)
        assertEquals(842f, back.y, 0.5f)
    }

    // Task 7: the page picture, 1:1 at normal size, is drawn on whole pixels, not blended across two.
    private fun a4() = Viewport(listOf(SizePt(595.28f, 841.89f)), viewWidthPx = 1860, viewHeightPx = 2369)

    @Test fun thePageStartsOnAWholePixel() {
        val v = a4() // fits by height: 1675.06 px wide, so it would start at x = 92.47
        val tl = v.pageToScreen().map(Pt(0f, 0f))
        assertEquals(92f, tl.x, 0f)
        assertEquals(0f, tl.y, 0f)
        // The pen's limit is of the same place.
        assertEquals(92f, v.pageBoundsPx()[0], 0f)
    }

    @Test fun screenToPageUndoesPageToScreen() {
        val v = a4()
        val points = listOf(Pt(0f, 0f), Pt(100.3f, 200.7f), Pt(595.28f, 841.89f))
        fun check() = points.forEach {
            val screen = v.pageToScreen().map(it)
            val back = v.screenToPage(screen.x, screen.y)
            assertEquals(it.x, back.x, 1e-3f)
            assertEquals(it.y, back.y, 1e-3f)
        }
        check()
        v.zoomAround(2.3f, 700f, 1000f)
        v.panBy(10.37f, -20.61f)
        val tl = v.pageToScreen().map(Pt(0f, 0f))
        assertEquals(Math.round(tl.x).toFloat(), tl.x, 0f)
        assertEquals(Math.round(tl.y).toFloat(), tl.y, 0f)
        check()
    }

    /** The page is shown on whole pixels, but where it is is kept finer: slow drags add up. */
    @Test fun aSlowMoveIsNotLost() {
        val v = a4()
        v.zoomAround(2f, 930f, 1184f)
        val before = v.pageToScreen().map(Pt(0f, 0f)).x
        repeat(10) { v.panBy(0.3f, 0f) }
        assertEquals(before - 3f, v.pageToScreen().map(Pt(0f, 0f)).x, 0f)
    }
}
