package com.fzizzle.epapernotes.ink

import java.util.Locale
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeOutlineTest {
    private fun pen(vararg p: StrokePoint) = Stroke(StrokeKind.PEN, p.toList())

    @Test fun penWidthFollowsPressure() {
        assertEquals(0.6f, StrokeStyle.of(StrokeKind.PEN).widthAt(0f), 1e-4f)
        assertEquals(2.0f, StrokeStyle.of(StrokeKind.PEN).widthAt(1f), 1e-4f)
    }

    @Test fun highlighterIsTranslucentYellow() {
        val s = StrokeStyle.of(StrokeKind.HIGHLIGHTER)
        assertEquals(0.4f, s.opacity, 1e-4f)
        assertArrayEquals(floatArrayOf(1f, 0.92f, 0.23f), s.rgb, 1e-4f)
    }

    @Test fun boundsPadByHalfWidth() {
        val b = StrokeOutline.bounds(pen(StrokePoint(100f, 100f, 1f), StrokePoint(200f, 100f, 1f)))
        assertEquals(Box(99f, 99f, 201f, 101f), b)
    }

    @Test fun singlePointIsADot() {
        val cs = StrokeOutline.contentStream(pen(StrokePoint(50f, 50f, 0.5f)))
        assertTrue(cs.startsWith("0.00 0.00 0.00 rg"))
        assertEquals(4, Regex(" c\\b").findAll(cs).count()) // one circle = 4 Bézier curves
        assertTrue(cs.trimEnd().endsWith("f"))
    }

    @Test fun usesDotDecimalsInAnyLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertFalse(StrokeOutline.contentStream(pen(StrokePoint(1.5f, 1.5f, 0.5f))).contains(','))
        } finally {
            Locale.setDefault(old)
        }
    }

    private fun curves(cs: String) = Regex(" c\\b").findAll(cs).count()

    @Test fun straightStrokeHasOnlyEndCaps() {
        val cs = StrokeOutline.contentStream(Stroke(StrokeKind.PEN, (0 until 200).map { StrokePoint(10f + it, 50f, 0.5f) }))
        assertEquals(8, curves(cs)) // two circles, one at each end
    }

    @Test fun sharpCornerGetsACircle() {
        val cs = StrokeOutline.contentStream(pen(StrokePoint(0f, 0f, 0.5f), StrokePoint(50f, 0f, 0.5f), StrokePoint(50f, 50f, 0.5f)))
        assertEquals(12, curves(cs)) // both ends plus the 90° corner
    }

    @Test fun longStrokeIsSmall() {
        val wavy = Stroke(StrokeKind.PEN, (0 until 200).map { i ->
            StrokePoint(50f + i * 2.5f, 100f + 4f * sin(i / 5f), 0.3f + 0.5f * ((i % 20) / 20f))
        })
        val length = StrokeOutline.contentStream(wavy).length
        assertTrue("$length characters", length < 6000)
    }

    /** A see-through highlighter must be filled once: overlapping fills would show darker blotches. */
    @Test fun strokeIsFilledInOneGo() {
        val zigzag = Stroke(StrokeKind.HIGHLIGHTER, (0 until 30).map { StrokePoint(10f + it * 3, if (it % 2 == 0) 0f else 20f, 1f) })
        val fills = Regex("(^|\\s)f(\\s|$)").findAll(StrokeOutline.contentStream(zigzag)).count()
        assertEquals(1, fills)
    }
}
