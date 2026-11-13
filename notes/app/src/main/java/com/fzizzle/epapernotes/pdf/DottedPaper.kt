package com.fzizzle.epapernotes.pdf

import java.util.Locale
import kotlin.math.floor

/**
 * The dots of a page of dotted paper, as a plain PDF content stream.
 *
 * The dots are a grid, centred on the page, with no dot nearer an edge than half a spacing.
 * Each dot is a zero-length line drawn with round caps, which PDF paints as a round dot.
 * Everything here is numbers and text, so it can be tested on the laptop.
 */
object DottedPaper {
    /** The gap between neighbouring dots: 5 mm, in points. */
    const val SPACING_PT = 14.17f

    /** The diameter of a dot, in points. */
    const val DOT_PT = 1.1f

    /** How dark the dots are, 0 black to 1 white. A first guess; the owner picks the final strength later. */
    const val GREY = 0.5f

    /** The centres of the dots, in PDF points measured from the bottom left, row by row. */
    fun dots(widthPt: Float, heightPt: Float): List<Pair<Float, Float>> {
        val columns = count(widthPt)
        val rows = count(heightPt)
        val left = (widthPt - (columns - 1) * SPACING_PT) / 2
        val bottom = (heightPt - (rows - 1) * SPACING_PT) / 2
        val result = ArrayList<Pair<Float, Float>>(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                result.add(Pair(left + column * SPACING_PT, bottom + row * SPACING_PT))
            }
        }
        return result
    }

    /** The drawing instructions for the page: every dot, in [grey]. */
    fun content(widthPt: Float, heightPt: Float, grey: Float = GREY): String {
        val out = StringBuilder()
        out.append("q\n1 J\n").append(num(DOT_PT)).append(" w\n").append(num(grey)).append(" G\n")
        for ((x, y) in dots(widthPt, heightPt)) {
            out.append(num(x)).append(' ').append(num(y)).append(" m ")
                .append(num(x)).append(' ').append(num(y)).append(" l S\n")
        }
        out.append("Q\n")
        return out.toString()
    }

    /**
     * How many dots fit along a side of [lengthPt]. n dots span (n - 1) spacings, and the two
     * margins must each be at least half a spacing, so (n - 1) + 1 <= length / spacing, which
     * makes n the whole number part of (length - spacing) / spacing, plus one.
     * The spare length is then shared equally by the two margins.
     */
    private fun count(lengthPt: Float): Int =
        floor((lengthPt - SPACING_PT) / SPACING_PT).toInt() + 1

    private fun num(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
}
