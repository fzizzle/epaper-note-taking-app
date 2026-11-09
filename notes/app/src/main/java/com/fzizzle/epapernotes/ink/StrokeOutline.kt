package com.fzizzle.epapernotes.ink

import java.util.Locale
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
 * The shape is simple on purpose: a filled four-sided piece between each pair of
 * neighbouring points, plus a filled circle at every point, which rounds the joins and ends.
 */
object StrokeOutline {

    /** The area the stroke covers, including its width. */
    fun bounds(stroke: Stroke): Box {
        val style = StrokeStyle.of(stroke.kind)
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in stroke.points) {
            val r = style.widthAt(p.pressure) / 2
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
        out.append("${n(style.rgb[0])} ${n(style.rgb[1])} ${n(style.rgb[2])} rg\n")

        val pts = stroke.points
        for (i in 0 until pts.size - 1) {
            segment(out, pts[i], pts[i + 1], style)
        }
        for (p in pts) {
            circle(out, p.x, p.y, style.widthAt(p.pressure) / 2)
        }
        return out.toString()
    }

    private fun segment(out: StringBuilder, a: StrokePoint, b: StrokePoint, style: StrokeStyle) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len == 0f) return // same spot twice: the circle at that point covers it
        // Unit normal: perpendicular to the segment, used to push each side out by half a width.
        val nx = -dy / len
        val ny = dx / len
        val ra = style.widthAt(a.pressure) / 2
        val rb = style.widthAt(b.pressure) / 2
        out.append("${n(a.x + nx * ra)} ${n(a.y + ny * ra)} m ")
        out.append("${n(b.x + nx * rb)} ${n(b.y + ny * rb)} l ")
        out.append("${n(b.x - nx * rb)} ${n(b.y - ny * rb)} l ")
        out.append("${n(a.x - nx * ra)} ${n(a.y - ny * ra)} l h f\n")
    }

    /** A circle made of four Bézier curves, the standard way to draw one in PDF. */
    private fun circle(out: StringBuilder, cx: Float, cy: Float, r: Float) {
        val k = 0.5523f * r // control-point distance that makes a quarter-circle
        out.append("${n(cx + r)} ${n(cy)} m ")
        out.append("${n(cx + r)} ${n(cy + k)} ${n(cx + k)} ${n(cy + r)} ${n(cx)} ${n(cy + r)} c ")
        out.append("${n(cx - k)} ${n(cy + r)} ${n(cx - r)} ${n(cy + k)} ${n(cx - r)} ${n(cy)} c ")
        out.append("${n(cx - r)} ${n(cy - k)} ${n(cx - k)} ${n(cy - r)} ${n(cx)} ${n(cy - r)} c ")
        out.append("${n(cx + k)} ${n(cy - r)} ${n(cx + r)} ${n(cy - k)} ${n(cx + r)} ${n(cy)} c h f\n")
    }

    // Locale.ROOT: PDF needs '.' as the decimal point, whatever language the tablet uses.
    private fun n(v: Float) = String.format(Locale.ROOT, "%.2f", v)
}
