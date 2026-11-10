package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.ink.Box
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBoundsTest {

    // A 600 × 800 pt page measured 200 px wide, as the writing screen measures it.
    private val page = SizePt(600f, 800f)
    private val width = 200
    private val height = 267
    private val pxPerPt = 200f / 600

    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    private val white = rgb(255, 255, 255)
    private val black = rgb(0, 0, 0)

    private fun paper(colour: Int = white) = IntArray(width * height) { colour }

    /** Paints the pixels x0 until x1, y0 until y1. */
    private fun IntArray.paint(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int = black) {
        for (y in y0 until y1) for (x in x0 until x1) this[y * width + x] = colour
    }

    private fun assertBox(expected: Box, actual: Box?) {
        requireNotNull(actual) { "expected $expected, got none" }
        assertArrayEquals(floatArrayOf(expected.x0, expected.y0, expected.x1, expected.y1), floatArrayOf(actual.x0, actual.y0, actual.x1, actual.y1), 0.01f)
    }

    @Test fun aBlankPageHasNoContent() {
        assertNull(ContentBounds.of(paper(), width, height))
        // A scan's paper is seldom pure white: nearly white is still paper.
        assertNull(ContentBounds.of(paper(rgb(250, 248, 246)), width, height))
    }

    @Test fun textInTheMiddleIsFound() {
        val pixels = paper().apply { paint(50, 80, 150, 180) }
        assertBox(Box(50f, 80f, 150f, 180f), ContentBounds.of(pixels, width, height))
    }

    /** A pale highlight is content too: its blue is well below white. */
    @Test fun aPaleColourCounts() {
        val pixels = paper().apply { paint(20, 30, 40, 50, colour = rgb(255, 235, 59)) }
        assertBox(Box(20f, 30f, 40f, 50f), ContentBounds.of(pixels, width, height))
    }

    @Test fun aSingleSpeckIsIgnored() {
        assertNull(ContentBounds.of(paper().apply { paint(5, 5, 6, 6) }, width, height))
        // Nor does one beside the text, on its rows, widen it.
        val pixels = paper().apply {
            paint(50, 80, 150, 180)
            paint(3, 100, 4, 101)
            paint(190, 10, 191, 11)
        }
        assertBox(Box(50f, 80f, 150f, 180f), ContentBounds.of(pixels, width, height))
    }

    @Test fun theBoxIsTheContentWithABorder() {
        // 50..150 × 60..200 px is 150..450 × 180..600 pt, and 12 pt more on each side.
        val box = ContentBounds.trim(page, Box(50f, 60f, 150f, 200f), pxPerPt)
        assertBox(Box(138f, 168f, 462f, 612f), box)
    }

    /**
     * Only a page number at the foot of the page: the box is half the page each way, round it
     * and on the page, so it shows at most twice the whole page's size, in a view of any shape.
     */
    @Test fun aTinyPrintedAreaIsEnlargedAtMostTwice() {
        // 95..105 × 250..256 px is 285..315 × 750..768 pt; 273..327 × 738..780 with the border.
        val box = ContentBounds.trim(page, Box(95f, 250f, 105f, 256f), pxPerPt)
        assertBox(Box(150f, 400f, 450f, 800f), box) // centred across, slid up off the bottom edge
        for ((w, h) in listOf(1860 to 2300, 2300 to 1860, 1200 to 1800)) {
            val trimmed = Viewport(listOf(page), w, h, fit = listOf(box)).pxPerPt()
            val whole = Viewport(listOf(page), w, h).pxPerPt()
            assertTrue("$w × $h: ${trimmed / whole}×", trimmed <= 2 * whole + 1e-4f)
        }
    }

    @Test fun aBlankPageIsNotTrimmed() {
        assertBox(Box(0f, 0f, 600f, 800f), ContentBounds.trim(page, null, pxPerPt))
    }

    /** A page that is one picture, edge to edge, gains nothing: it's shown whole. */
    @Test fun aFullBleedPageIsNotTrimmed() {
        // 24 pt in from each edge: with the border that's 93% of the page, not worth cutting.
        val pixels = paper().apply { paint(8, 8, width - 8, height - 8, colour = rgb(90, 120, 160)) }
        val content = ContentBounds.of(pixels, width, height)
        assertBox(Box(0f, 0f, 600f, 800f), ContentBounds.trim(page, content, pxPerPt))
    }

    @Test fun theBorderNeverLeavesThePage() {
        // Content right in the top-left corner, and right at the bottom-right one.
        assertBox(Box(0f, 0f, 372f, 492f), ContentBounds.trim(page, Box(1f, 1f, 120f, 160f), pxPerPt))
        assertBox(Box(228f, 309f, 600f, 800f), ContentBounds.trim(page, Box(80f, 107f, 200f, 267f), pxPerPt))
    }

    /** The page is measured with its ink, so a note written in the margin is kept in view. */
    @Test fun inkInTheMarginWidensTheTrim() {
        val text = paper().apply { paint(50, 80, 150, 180) }
        val withNote = text.copyOf().apply { paint(8, 120, 11, 150) } // a short stroke near the left edge
        val before = ContentBounds.trim(page, ContentBounds.of(text, width, height), pxPerPt)
        val content = ContentBounds.of(withNote, width, height)
        assertEquals(8f, requireNotNull(content).x0, 0.01f)
        val after = ContentBounds.trim(page, content, pxPerPt)
        assertTrue("the trim reaches the stroke: ${after.x0}", after.x0 <= 8 / pxPerPt)
        assertTrue("and is wider than without it", after.x0 < before.x0)
        assertEquals(before.x1, after.x1, 0.01f)
    }
}
