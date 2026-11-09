package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.StrokeStyle
import kotlin.math.hypot
import kotlin.math.max

/** Which items an eraser trail touches. All in PDF space. Touching any part takes the whole item. */
object Eraser {
    const val RADIUS_PT = 3f

    fun hits(items: List<Item>, trail: List<Pt>): List<Item> =
        if (trail.isEmpty()) emptyList() else items.filter { hit(it, trail) }

    private fun hit(item: Item, trail: List<Pt>): Boolean = when (item) {
        is ImageItem -> trail.any { it.x in item.box.x0..item.box.x1 && it.y in item.box.y0..item.box.y1 }
        is InkItem -> {
            val s = item.stroke
            val style = StrokeStyle.of(s.kind)
            val pts = s.points
            pts.indices.any { i ->
                val a = pts[i]
                val b = pts[minOf(i + 1, pts.lastIndex)] // a lone point is a segment of length 0
                val half = max(style.widthAt(a.pressure, s.widthScale), style.widthAt(b.pressure, s.widthScale)) / 2
                trail.any { distanceToSegment(it.x, it.y, a.x, a.y, b.x, b.y) <= RADIUS_PT + half }
            }
        }
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
