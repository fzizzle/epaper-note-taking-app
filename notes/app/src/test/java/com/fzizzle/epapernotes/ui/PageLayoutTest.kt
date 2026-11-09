package com.fzizzle.epapernotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {
    private val portrait = SizePt(612f, 792f)
    private val landscape = SizePt(792f, 612f)
    private val layout = PageLayout(listOf(portrait, landscape, portrait), viewWidthPx = 1860, gapPx = 16)

    @Test fun pagesStackWithGaps() {
        assertEquals(0f, layout.pageTop(0), 0.01f)
        assertEquals(792f * 1860 / 612, layout.pageHeight(0), 0.01f)
        assertEquals(layout.pageHeight(0) + 16, layout.pageTop(1), 0.01f)
        assertEquals(layout.pageTop(2) + layout.pageHeight(2), layout.contentHeight, 0.01f)
    }

    @Test fun landscapePageFillsWidth() {
        assertEquals(1860f / 792, layout.pageScale(1), 1e-4f)
        assertEquals(612f * 1860 / 792, layout.pageHeight(1), 0.01f)
    }

    @Test fun pageAtFindsPage() {
        assertEquals(0, layout.pageAt(10f))
        assertEquals(1, layout.pageAt(layout.pageTop(1) + 5))
        assertEquals(2, layout.pageAt(layout.contentHeight + 500)) // below the end: the last page
        assertEquals(0, layout.pageAt(-20f))
    }

    @Test fun visiblePagesRange() {
        assertEquals(0..1, layout.visiblePages(layout.pageHeight(0) - 10, layout.pageTop(1) + 10))
    }
}
