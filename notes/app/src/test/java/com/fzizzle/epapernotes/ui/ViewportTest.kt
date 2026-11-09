package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {
    private val layout = PageLayout(listOf(SizePt(612f, 792f), SizePt(792f, 612f), SizePt(612f, 792f)), viewWidthPx = 1860)
    private fun viewport() = Viewport(layout, viewHeightPx = 2300)

    @Test fun scrollIsClamped() {
        val v = viewport()
        v.scrollBy(0f, -100f)
        assertEquals(0f, v.scrollY, 0.01f)
        v.scrollBy(0f, 1e6f)
        assertEquals(layout.contentHeight - 2300, v.scrollY, 0.01f)
    }

    @Test fun noSidewaysScrollAtZoomOne() {
        val v = viewport()
        v.scrollBy(300f, 0f)
        assertEquals(0f, v.scrollX, 0.01f)
    }

    @Test fun zoomKeepsFocusPointStill() {
        val v = viewport()
        v.scrollBy(0f, 1000f)
        val (pageBefore, ptBefore) = v.screenToPage(600f, 900f)
        v.zoomAround(2f, 600f, 900f)
        val (pageAfter, ptAfter) = v.screenToPage(600f, 900f)
        assertEquals(pageBefore, pageAfter)
        assertEquals(ptBefore.x, ptAfter.x, 0.01f)
        assertEquals(ptBefore.y, ptAfter.y, 0.01f)
    }

    @Test fun zoomIsClampedTo1And4() {
        val v = viewport()
        v.zoomAround(10f, 0f, 0f)
        assertEquals(4f, v.zoom, 1e-4f)
        v.zoomAround(0.01f, 0f, 0f)
        assertEquals(1f, v.zoom, 1e-4f)
    }

    @Test fun currentPageIsUnderCentre() {
        val v = viewport()
        assertEquals(0, v.currentPage())
        v.scrollBy(0f, layout.pageTop(1) - 1000)
        assertEquals(1, v.currentPage())
    }

    /** The whole chain: PDF point → display point → screen pixel, and back, zoomed, scrolled and rotated. */
    @Test fun mapsThroughZoomScrollAndRotation() {
        // Page 1 is a portrait page with MediaBox (100,100)-(712,892), turned 90°. This is the
        // transform PdfFile.pdfToDisplay gives for it (checked on the tablet in plan 2, Task 2).
        val pdfToDisplay = Affine(a = 0f, b = 1f, c = 1f, d = 0f, e = -100f, f = -100f)
        val v = viewport()
        v.zoomAround(2f, 900f, 1200f)
        v.scrollBy(150f, layout.pageTop(1) * 2 - 800)

        val screen = v.pageToScreen(1).map(pdfToDisplay.map(Pt(150f, 842f)))
        val (page, display) = v.screenToPage(screen.x, screen.y)
        val back = pdfToDisplay.inverse().map(display)
        assertEquals(1, page)
        assertEquals(150f, back.x, 0.5f)
        assertEquals(842f, back.y, 0.5f)
    }

    /** Reopening a document restores the page at the top of the screen, so the same view comes back. */
    @Test fun topPageRoundTripsThroughScrollToPage() {
        val v = viewport()
        v.scrollBy(0f, layout.pageTop(1) + 50)
        val saved = v.topPage()
        val reopened = viewport()
        reopened.scrollToPage(saved)
        assertEquals(v.currentPage(), reopened.currentPage())
        assertEquals(1, saved)
    }
}
