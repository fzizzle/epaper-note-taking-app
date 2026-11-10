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

    @Test fun widthScaleWidensBounds() {
        val one = StrokeOutline.bounds(pen(StrokePoint(100f, 100f, 1f)))
        val two = StrokeOutline.bounds(Stroke(StrokeKind.PEN, listOf(StrokePoint(100f, 100f, 1f)), widthScale = 2f))
        assertEquals((one.x1 - one.x0) * 2, two.x1 - two.x0, 1e-4f)
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

    /** One closed piece of the drawing: its corner points in order (curves flattened to short lines). */
    private fun pieces(cs: String): List<List<Pair<Float, Float>>> {
        val done = mutableListOf<List<Pair<Float, Float>>>()
        var piece = mutableListOf<Pair<Float, Float>>()
        val numbers = mutableListOf<Float>()
        for (token in cs.trim().split(Regex("\\s+"))) {
            val number = token.toFloatOrNull()
            if (number != null) {
                numbers += number
                continue
            }
            when (token) {
                "m", "l" -> piece += numbers[0] to numbers[1]
                "c" -> {
                    val (x0, y0) = piece.last()
                    for (step in 1..8) {
                        val t = step / 8f
                        val u = 1 - t
                        piece += (u * u * u * x0 + 3 * u * u * t * numbers[0] + 3 * u * t * t * numbers[2] + t * t * t * numbers[4]) to
                            (u * u * u * y0 + 3 * u * u * t * numbers[1] + 3 * u * t * t * numbers[3] + t * t * t * numbers[5])
                    }
                }
                "h" -> {
                    done += piece
                    piece = mutableListOf()
                }
            }
            numbers.clear()
        }
        return done
    }

    /** Twice the area inside a closed piece: positive going counter-clockwise, negative clockwise. */
    private fun signedArea(piece: List<Pair<Float, Float>>): Float =
        piece.indices.sumOf { i ->
            val (x0, y0) = piece[i]
            val (x1, y1) = piece[(i + 1) % piece.size]
            (x0 * y1 - x1 * y0).toDouble()
        }.toFloat()

    /**
     * How many times the pieces go round the point, clockwise ones counting against
     * counter-clockwise ones. A PDF viewer fills a point only where this isn't zero.
     */
    private fun timesRound(pieces: List<List<Pair<Float, Float>>>, x: Float, y: Float): Int {
        var count = 0
        for (piece in pieces) {
            for (i in piece.indices) {
                val (x0, y0) = piece[i]
                val (x1, y1) = piece[(i + 1) % piece.size]
                val side = (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0)
                if (y0 <= y && y1 > y && side > 0) count++
                if (y0 > y && y1 <= y && side < 0) count--
            }
        }
        return count
    }

    /** A highlighter line to the right, then sharply back up and left. */
    private val hook = Stroke(StrokeKind.HIGHLIGHTER, listOf(StrokePoint(100f, 100f, 1f), StrokePoint(300f, 100f, 1f), StrokePoint(200f, 250f, 1f)))

    /**
     * Everything is filled in one go, and a piece going the other way round cancels whatever it
     * overlaps. The caps went against the body, which left a hole just inside each end and corner.
     */
    @Test fun capsTurnTheSameWayAsTheBody() {
        val areas = pieces(StrokeOutline.contentStream(hook)).map { signedArea(it) }
        assertEquals(4, areas.size) // the body, two ends and the corner
        assertTrue("areas: $areas", areas.all { it < 0 } || areas.all { it > 0 })
    }

    @Test fun strokeIsSolidJustInsideItsEndsAndCorner() {
        val pieces = pieces(StrokeOutline.contentStream(hook))
        val r = StrokeStyle.of(StrokeKind.HIGHLIGHTER).widthAt(1f) / 2
        // Half a radius in from each end along the line, and at the corner itself.
        val inside = listOf(100f + r / 2 to 100f, 300f - r / 2 to 100f, 300f to 100f, 200f + r * 0.28f to 250f - r * 0.42f)
        inside.forEach { (x, y) -> assertTrue("a hole at $x,$y", timesRound(pieces, x, y) != 0) }
        assertEquals("nothing well away from the line", 0, timesRound(pieces, 150f, 100f + 3 * r))
    }

    /** No holes anywhere along a line: every spot within most of the line's half-width of it is filled. */
    private fun assertSolidAlong(stroke: Stroke) {
        val pieces = pieces(StrokeOutline.contentStream(stroke))
        val reach = StrokeStyle.of(stroke.kind).widthAt(1f, stroke.widthScale) / 2 * 0.8f
        val holes = mutableListOf<String>()
        for (i in 0 until stroke.points.size - 1) {
            val a = stroke.points[i]
            val b = stroke.points[i + 1]
            for (step in 0..4) {
                val x = a.x + (b.x - a.x) * step / 4
                val y = a.y + (b.y - a.y) * step / 4
                // The spot itself, and eight spots round it. Off the 0.1 pt grid the drawing is rounded to.
                for (dx in -1..1) for (dy in -1..1) {
                    val px = x + dx * reach * 0.7f + 0.013f
                    val py = y + dy * reach * 0.7f + 0.017f
                    if (timesRound(pieces, px, py) == 0) holes += "$px,$py"
                }
            }
        }
        assertEquals("holes", emptyList<String>(), holes)
    }

    /** A line through [corners] with a point every [spacing] pt or so, the way the pen reports one. */
    private fun through(kind: StrokeKind, spacing: Float, vararg corners: Pair<Float, Float>): Stroke {
        val points = mutableListOf(StrokePoint(corners[0].first, corners[0].second, 1f))
        for (i in 1 until corners.size) {
            val (x0, y0) = corners[i - 1]
            val (x1, y1) = corners[i]
            val steps = maxOf(1, Math.round(kotlin.math.hypot(x1 - x0, y1 - y0) / spacing))
            for (step in 1..steps) points += StrokePoint(x0 + (x1 - x0) * step / steps, y0 + (y1 - y0) * step / steps, 1f)
        }
        return Stroke(kind, points)
    }

    @Test fun zigZagHasNoHoles() {
        val zigzag = (0 until 8).map { 10f + it * 30 to if (it % 2 == 0) 0f else 60f }.toTypedArray()
        val tight = (0 until 12).map { 10f + it * 5 to if (it % 2 == 0) 0f else 40f }.toTypedArray()
        for (spacing in listOf(0.7f, 1.5f, 3f)) {
            assertSolidAlong(through(StrokeKind.HIGHLIGHTER, spacing, *zigzag))
            assertSolidAlong(through(StrokeKind.HIGHLIGHTER, spacing, *tight))
            assertSolidAlong(through(StrokeKind.HIGHLIGHTER, spacing, 100f to 100f, 300f to 100f, 100f to 100f)) // there and back
        }
    }
}
