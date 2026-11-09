package com.fzizzle.epapernotes.ui

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint

/** Turns the pen points Onyx reports (screen pixels, raw pressure) into a stroke on one page. */
object PenPoints {
    /** The pen's full-pressure reading. The probe logged values up to this (EMR pens report 0..4095). */
    const val MAX_PRESSURE = 4095f

    /**
     * [screen] points are `[x, y, rawPressure]` in the page view's pixels. [toPdf] says which
     * page a pixel is on and where in PDF space.
     *
     * A stroke belongs to the page where it started. Points that run onto another page are
     * left out, because one annotation can only live on one page. Returns null if nothing is left.
     */
    fun toStroke(kind: StrokeKind, screen: List<FloatArray>, toPdf: (x: Float, y: Float) -> Pair<Int, Pt>): Pair<Int, Stroke>? {
        if (screen.isEmpty()) return null
        val page = toPdf(screen[0][0], screen[0][1]).first
        val points = screen.mapNotNull { p ->
            val (onPage, pdf) = toPdf(p[0], p[1])
            if (onPage != page) return@mapNotNull null
            StrokePoint(pdf.x, pdf.y, (p[2] / MAX_PRESSURE).coerceIn(0f, 1f))
        }
        if (points.isEmpty()) return null
        return page to Stroke(kind, points)
    }
}
