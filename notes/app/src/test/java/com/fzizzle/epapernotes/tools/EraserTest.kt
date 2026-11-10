package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.ink.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

class EraserTest {
    private fun line(kind: StrokeKind = StrokeKind.PEN, y: Float = 100f) =
        InkItem("s", Stroke(kind, listOf(StrokePoint(0f, y, 0.5f), StrokePoint(200f, y, 0.5f))))

    @Test fun touchingAnyPartRemovesTheWholeStroke() {
        val s = line()
        assertEquals(listOf<Any>(s), Eraser.hits(listOf(s), listOf(Pt(100f, 101f), Pt(100f, 102f))))
    }

    @Test fun nearMissKeepsTheStroke() {
        assertTrue(Eraser.hits(listOf(line()), listOf(Pt(100f, 106f))).isEmpty())
    }

    @Test fun highlighterIsHitAtItsEdge() {
        val h = line(StrokeKind.HIGHLIGHTER)
        assertEquals(1, Eraser.hits(listOf(h), listOf(Pt(100f, 105f))).size)
    }

    @Test fun tapHitsADot() {
        val dot = InkItem("d", Stroke(StrokeKind.PEN, listOf(StrokePoint(50f, 50f, 0.5f))))
        assertEquals(1, Eraser.hits(listOf(dot), listOf(Pt(52f, 50f))).size)
        assertTrue(Eraser.hits(listOf(dot), listOf(Pt(60f, 50f))).isEmpty())
    }

    @Test fun imageIsHitInside() {
        val img = ImageItem("i", Box(0f, 0f, 50f, 50f), 0, byteArrayOf(1))
        assertEquals(1, Eraser.hits(listOf(img), listOf(Pt(25f, 25f))).size)
        assertTrue(Eraser.hits(listOf(img), listOf(Pt(60f, 25f))).isEmpty())
    }

    @Test fun fastSwipeBetweenSamplesStillHits() {
        val s = line()
        assertEquals(1, Eraser.hits(listOf(s), listOf(Pt(100f, 80f), Pt(100f, 120f))).size)
        val img = ImageItem("i", Box(0f, 0f, 50f, 50f), 0, byteArrayOf(1))
        assertEquals(1, Eraser.hits(listOf(img), listOf(Pt(-20f, 25f), Pt(70f, 25f))).size)
        assertTrue(Eraser.hits(listOf(img), listOf(Pt(-20f, 60f), Pt(70f, 60f))).isEmpty())
    }

    /** The eraser only tests trail segments near each stroke. These are the cases where that could go wrong. */
    @Test fun onlyTheRightStrokesAreHitAmongMany() {
        val far = InkItem("far", Stroke(StrokeKind.PEN, listOf(StrokePoint(500f, 900f, 1f), StrokePoint(600f, 900f, 1f))))
        // Thin (no pressure) everywhere except its far end, which is wide.
        fun taper(id: String, y: Float) = InkItem(id, Stroke(StrokeKind.PEN, listOf(0f, 50f, 100f, 150f).map { StrokePoint(it, y, 0f) } + StrokePoint(200f, y, 1f)))
        val nearThin = taper("nearThin", 200f) // hit at x=10, 3.2 pt off: within 3 + 0.3
        val missThin = taper("missThin", 300f) // 3.8 pt off at x=10: only the wide end would reach that
        val dot = InkItem("dot", Stroke(StrokeKind.PEN, listOf(StrokePoint(400f, 600f, 0.5f))))
        val items = listOf(far, nearThin, missThin, dot)

        assertEquals(listOf(nearThin), Eraser.hits(items, listOf(Pt(10f, 203.2f))))
        assertTrue(Eraser.hits(items, listOf(Pt(10f, 303.8f))).isEmpty())
        // A long trail whose points are all far from the dot; only its middle segment crosses it.
        val long = listOf(Pt(0f, 500f), Pt(400f, 500f), Pt(400f, 700f), Pt(800f, 700f))
        assertEquals(listOf(dot), Eraser.hits(items, long))
    }

    /** Random strokes and swipes give the same hits as testing every pair of segments. */
    @Test fun sameHitsAsTestingEveryPair() {
        val random = Random(7)
        fun walk(n: Int, step: Float): List<Pt> {
            var x = random.nextFloat() * 400f
            var y = random.nextFloat() * 400f
            return List(n) { Pt(x, y).also { x += (random.nextFloat() - 0.5f) * step; y += (random.nextFloat() - 0.5f) * step } }
        }
        repeat(200) { round ->
            val items = List(8) { k ->
                val kind = if (k % 3 == 0) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
                InkItem("s$k", Stroke(kind, walk(1 + random.nextInt(30), 20f).map { StrokePoint(it.x, it.y, random.nextFloat()) }))
            }
            val trail = walk(1 + random.nextInt(20), 60f)
            assertEquals("round $round", items.filter { allPairsHit(it, trail) }, Eraser.hits(items, trail))
        }
    }

    private fun allPairsHit(item: InkItem, trail: List<Pt>): Boolean {
        val s = item.stroke
        val style = StrokeStyle.of(s.kind)
        val segs = if (trail.size == 1) listOf(trail[0] to trail[0]) else trail.zipWithNext()
        return s.points.indices.any { i ->
            val a = s.points[i]
            val b = s.points[minOf(i + 1, s.points.lastIndex)]
            val half = max(style.widthAt(a.pressure, s.widthScale), style.widthAt(b.pressure, s.widthScale)) / 2
            segs.any { (p, q) -> segmentGap(p, q, Pt(a.x, a.y), Pt(b.x, b.y)) <= Eraser.RADIUS_PT + half }
        }
    }

    private fun segmentGap(p: Pt, q: Pt, a: Pt, b: Pt): Float {
        fun side(o: Pt, u: Pt, v: Pt) = (u.x - o.x) * (v.y - o.y) - (u.y - o.y) * (v.x - o.x)
        if (side(p, q, a) * side(p, q, b) < 0f && side(a, b, p) * side(a, b, q) < 0f) return 0f
        fun toSeg(o: Pt, u: Pt, v: Pt): Float {
            val dx = v.x - u.x
            val dy = v.y - u.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((o.x - u.x) * dx + (o.y - u.y) * dy) / len2).coerceIn(0f, 1f)
            return hypot(o.x - (u.x + t * dx), o.y - (u.y + t * dy))
        }
        return minOf(toSeg(p, a, b), toSeg(q, a, b), toSeg(a, p, q), toSeg(b, p, q))
    }
}
