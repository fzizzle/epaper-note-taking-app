package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.StrokeStyle
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Which items an eraser trail touches. All in PDF space. Touching any part takes the whole item.
 * The trail is tested as line segments between its samples, so a fast swipe can't jump over a
 * thin stroke. A one-point trail is a tap.
 */
object Eraser {
    const val RADIUS_PT = 3f

    fun hits(items: List<Item>, trail: List<Pt>): List<Item> {
        if (trail.isEmpty()) return emptyList()
        val segments = trailSegments(trail)
        return items.filter { hit(it, segments) }
    }

    private fun hit(item: Item, trail: List<Pair<Pt, Pt>>): Boolean = when (item) {
        is ImageItem -> trail.any { (p, q) -> touchesBox(p, q, item.box) }
        is InkItem -> {
            val s = item.stroke
            val style = StrokeStyle.of(s.kind)
            val pts = s.points
            // Only the bits of the trail that come near the stroke at all. A page can hold
            // thousands of points and a swipe hundreds, and testing every pair took seconds on
            // the tablet (Task 7's launch check).
            val reach = RADIUS_PT + pts.maxOf { style.widthAt(it.pressure, s.widthScale) } / 2
            val near = Box(
                pts.minOf { it.x } - reach, pts.minOf { it.y } - reach,
                pts.maxOf { it.x } + reach, pts.maxOf { it.y } + reach,
            )
            val close = trail.filter { (p, q) -> min(p.x, q.x) <= near.x1 && max(p.x, q.x) >= near.x0 && min(p.y, q.y) <= near.y1 && max(p.y, q.y) >= near.y0 }
            close.isNotEmpty() && pts.indices.any { i ->
                val a = pts[i]
                val b = pts[minOf(i + 1, pts.lastIndex)] // a lone point is a segment of length 0
                // The wider end, so a pressure change along the segment never makes it too easy to miss.
                val half = max(style.widthAt(a.pressure, s.widthScale), style.widthAt(b.pressure, s.widthScale)) / 2
                val pa = Pt(a.x, a.y)
                val pb = Pt(b.x, b.y)
                close.any { (p, q) -> segmentDistance(p, q, pa, pb) <= RADIUS_PT + half }
            }
        }
    }

    /** Consecutive pairs of trail points; a single point pairs with itself. */
    private fun trailSegments(trail: List<Pt>): List<Pair<Pt, Pt>> =
        if (trail.size == 1) listOf(trail[0] to trail[0]) else trail.zipWithNext()

    /** True when the segment p-q has a point inside [box] (Liang-Barsky clipping). */
    private fun touchesBox(p: Pt, q: Pt, box: Box): Boolean {
        var t0 = 0f
        var t1 = 1f
        val d = floatArrayOf(q.x - p.x, q.y - p.y)
        val lo = floatArrayOf(box.x0 - p.x, box.y0 - p.y)
        val hi = floatArrayOf(box.x1 - p.x, box.y1 - p.y)
        for (k in 0..1) {
            if (d[k] == 0f) {
                if (lo[k] > 0f || hi[k] < 0f) return false
            } else {
                var ta = lo[k] / d[k]
                var tb = hi[k] / d[k]
                if (ta > tb) { val t = ta; ta = tb; tb = t }
                t0 = max(t0, ta)
                t1 = min(t1, tb)
                if (t0 > t1) return false
            }
        }
        return true
    }

    private fun segmentDistance(p: Pt, q: Pt, a: Pt, b: Pt): Float {
        if (crosses(p, q, a, b)) return 0f
        return minOf(
            distanceToSegment(p.x, p.y, a.x, a.y, b.x, b.y),
            distanceToSegment(q.x, q.y, a.x, a.y, b.x, b.y),
            distanceToSegment(a.x, a.y, p.x, p.y, q.x, q.y),
            distanceToSegment(b.x, b.y, p.x, p.y, q.x, q.y),
        )
    }

    private fun crosses(p: Pt, q: Pt, a: Pt, b: Pt): Boolean {
        fun side(o: Pt, u: Pt, v: Pt) = (u.x - o.x) * (v.y - o.y) - (u.y - o.y) * (v.x - o.x)
        val d1 = side(p, q, a); val d2 = side(p, q, b); val d3 = side(a, b, p); val d4 = side(a, b, q)
        return d1 * d2 < 0f && d3 * d4 < 0f
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
