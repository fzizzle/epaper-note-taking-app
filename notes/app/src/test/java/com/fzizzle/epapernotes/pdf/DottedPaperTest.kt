package com.fzizzle.epapernotes.pdf

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DottedPaperTest {
    private val w = 595f
    private val h = 842f

    @Test
    fun a4HasAGridOf41By59() {
        val dots = DottedPaper.dots(w, h)
        assertEquals(41, dots.map { it.first }.distinct().size)
        assertEquals(59, dots.map { it.second }.distinct().size)
        assertEquals(41 * 59, dots.size)
    }

    @Test
    fun dotsAreCentred() {
        val dots = DottedPaper.dots(w, h)
        val left = dots.minOf { it.first }
        val right = w - dots.maxOf { it.first }
        val top = dots.minOf { it.second }
        val bottom = h - dots.maxOf { it.second }
        assertTrue(abs(left - right) < 0.01f)
        assertTrue(abs(top - bottom) < 0.01f)
    }

    @Test
    fun noDotNearerAnEdgeThanHalfASpacing() {
        val half = DottedPaper.SPACING_PT / 2
        for ((x, y) in DottedPaper.dots(w, h)) {
            assertTrue(x >= half - 0.001f && w - x >= half - 0.001f)
            assertTrue(y >= half - 0.001f && h - y >= half - 0.001f)
        }
    }

    @Test
    fun contentHasOneLinePerDot() {
        val content = DottedPaper.content(w, h)
        assertEquals(DottedPaper.dots(w, h).size, content.split(" l S").size - 1)
        assertTrue(content.startsWith("q"))
        assertTrue(content.trimEnd().endsWith("Q"))
        assertFalse(content.contains(','))
    }
}
