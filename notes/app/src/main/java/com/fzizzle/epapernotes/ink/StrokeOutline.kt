package com.fzizzle.epapernotes.ink

import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a stroke into PDF drawing commands that fill its outline, so the line gets thicker
 * where the pen pressed harder.
 *
 * Other PDF viewers draw an ink annotation from these commands (its "appearance"). Without
 * them they'd draw one fixed width and the pressure would be lost. MuPDF draws the same
 * commands inside the app, so the app and other viewers look alike.
 *
 * The shape is an outline: along the left edge of the stroke, then back along the right
 * edge, each pushed out by half the width at that point. Where the line turns sharply the
 * outline stops and a new one starts. Circles round off the two ends and cover each sharp turn. Plan 1 drew a circle at every point, which
 * made each stroke about 20 KB; this is a fraction of that.
 *
 * All the pieces are filled together, once, at the end. Filled one by one, a see-through
 * highlighter got darker wherever two pieces overlapped.
 */
object StrokeOutline {

    /** A turn sharper than this gets a circle, so the corner stays round instead of notched. */
    private val SHARP_TURN_COS = cos(Math.toRadians(45.0)).toFloat()

    /** The area the stroke covers, including its width. */
    fun bounds(stroke: Stroke): Box {
        val style = StrokeStyle.of(stroke.kind)
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in stroke.points) {
            val r = style.widthAt(p.pressure, stroke.widthScale) / 2
            x0 = min(x0, p.x - r)
            y0 = min(y0, p.y - r)
            x1 = max(x1, p.x + r)
            y1 = max(y1, p.y + r)
        }
        return Box(x0, y0, x1, y1)
    }

    /** PDF content-stream operators, in PDF user space. */
    fun contentStream(stroke: Stroke): String {
        val style = StrokeStyle.of(stroke.kind)
        val out = StringBuilder()
        out.append("${colour(style.rgb[0])} ${colour(style.rgb[1])} ${colour(style.rgb[2])} rg\n")

        val pts = withoutRepeats(stroke.points)
        if (pts.size == 1) {
            circle(out, pts[0].x, pts[0].y, style.widthAt(pts[0].pressure, stroke.widthScale) / 2)
            out.append("f\n")
            return out.toString()
        }

        // The outline is cut at every sharp turn, and a circle covers the cut. Carried round the
        // turn in one piece, the outline was pinched there (its two edges nearly met), which
        // left unpainted slivers beside the circle when the points were far apart.
        val sharp = (1 until pts.size - 1).filter { isSharpTurn(pts[it - 1], pts[it], pts[it + 1]) }
        val cuts = listOf(0) + sharp + listOf(pts.size - 1)
        for (i in 0 until cuts.size - 1) outline(out, pts.subList(cuts[i], cuts[i + 1] + 1), style, stroke.widthScale)
        for (i in cuts) circle(out, pts[i].x, pts[i].y, style.widthAt(pts[i].pressure, stroke.widthScale) / 2)
        // Non-zero fill: every piece, and every overlap, is filled exactly once. That only
        // holds while all the pieces go round the same way. Where a piece going one way
        // overlaps a piece going the other, the two cancel and the overlap is left unpainted:
        // the circles used to go against the outline, which left a hole inside each end and corner.
        out.append("f\n")
        return out.toString()
    }

    /**
     * Forward along the left edge, back along the right, which is clockwise in PDF space (y up).
     * Non-zero fill keeps tight curls solid.
     */
    private fun outline(out: StringBuilder, pts: List<StrokePoint>, style: StrokeStyle, scale: Float) {
        val normals = pts.indices.map { normalAt(pts, it) }
        for (i in pts.indices) {
            val r = style.widthAt(pts[i].pressure, scale) / 2
            val (nx, ny) = normals[i]
            out.append("${n(pts[i].x + nx * r)} ${n(pts[i].y + ny * r)} ${if (i == 0) "m" else "l"} ")
        }
        for (i in pts.indices.reversed()) {
            val r = style.widthAt(pts[i].pressure, scale) / 2
            val (nx, ny) = normals[i]
            out.append("${n(pts[i].x - nx * r)} ${n(pts[i].y - ny * r)} l ")
        }
        out.append("h\n")
    }

    /** The direction "sideways" at a point: the average of its neighbouring segments' normals. */
    private fun normalAt(pts: List<StrokePoint>, i: Int): Pair<Float, Float> {
        val before = if (i > 0) segmentNormal(pts[i - 1], pts[i]) else null
        val after = if (i < pts.size - 1) segmentNormal(pts[i], pts[i + 1]) else null
        if (before == null) return after!!
        if (after == null) return before
        val sx = before.first + after.first
        val sy = before.second + after.second
        val len = hypot(sx, sy)
        // The line doubles back on itself: the two normals cancel out, so keep the first one.
        if (len < 1e-3f) return before
        return sx / len to sy / len
    }

    private fun segmentNormal(a: StrokePoint, b: StrokePoint): Pair<Float, Float> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        return -dy / len to dx / len
    }

    private fun isSharpTurn(a: StrokePoint, b: StrokePoint, c: StrokePoint): Boolean {
        val ux = b.x - a.x
        val uy = b.y - a.y
        val vx = c.x - b.x
        val vy = c.y - b.y
        val cosine = (ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))
        return cosine < SHARP_TURN_COS
    }

    private fun withoutRepeats(points: List<StrokePoint>): List<StrokePoint> =
        points.filterIndexed { i, p -> i == 0 || p.x != points[i - 1].x || p.y != points[i - 1].y }

    /**
     * A circle made of four Bézier curves, the standard way to draw one in PDF. It goes round
     * clockwise (in PDF space, y up), the same way as [outline]: see the fill in [contentStream].
     */
    private fun circle(out: StringBuilder, cx: Float, cy: Float, r: Float) {
        val k = 0.5523f * r // control-point distance that makes a quarter-circle
        out.append("${n(cx + r)} ${n(cy)} m ")
        out.append("${n(cx + r)} ${n(cy - k)} ${n(cx + k)} ${n(cy - r)} ${n(cx)} ${n(cy - r)} c ")
        out.append("${n(cx - k)} ${n(cy - r)} ${n(cx - r)} ${n(cy - k)} ${n(cx - r)} ${n(cy)} c ")
        out.append("${n(cx - r)} ${n(cy + k)} ${n(cx - k)} ${n(cy + r)} ${n(cx)} ${n(cy + r)} c ")
        out.append("${n(cx + k)} ${n(cy + r)} ${n(cx + r)} ${n(cy + k)} ${n(cx + r)} ${n(cy)} c h\n")
    }

    // Locale.ROOT: PDF needs '.' as the decimal point, whatever language the tablet uses.
    // Coordinates to 0.1 pt: even at the maximum 4x zoom that's under 2 screen pixels, and it
    // keeps each stroke's drawing small. The exact points are kept in the stroke data anyway.
    private fun n(v: Float) = String.format(Locale.ROOT, "%.1f", v)

    private fun colour(v: Float) = String.format(Locale.ROOT, "%.2f", v)
}
