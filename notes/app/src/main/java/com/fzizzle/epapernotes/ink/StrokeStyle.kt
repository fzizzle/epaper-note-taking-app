package com.fzizzle.epapernotes.ink

/**
 * How a kind of stroke looks: colour, see-through-ness and how width follows pressure.
 * Widths are in PDF points (1/72 inch).
 *
 * The pen follows pressure squared, as Onyx's fountain pen draws its live line (FountainWidthCheck,
 * 2026-11-10), so a stroke keeps its width when the app's copy replaces the live line. With a
 * straight line firm strokes came out up to 40% thinner once saved (owner, 2026-11-10). The
 * system draws its live line wider than the SDK's own curve, though: photos before and after a
 * tool tap showed the copy at 82-87% of the live width (2026-11-12, findings log), so the saved
 * pen is 1.2 x that curve while the live line stays as it was ([liveSpan]).
 */
class StrokeStyle(
    val rgb: FloatArray,
    val opacity: Float,
    val minWidth: Float,
    val maxWidth: Float,
    private val squared: Boolean = false,
    /** The pressure range, in points, the live line is set from; the saved range by default. */
    private val liveSpan: Float = maxWidth - minWidth,
) {

    /**
     * Width at a point: between [minWidth] (no pressure) and [maxWidth] (full), straight-line or
     * by pressure squared, times [scale] (the stroke's `widthScale`).
     */
    fun widthAt(pressure: Float, scale: Float = 1f): Float {
        val p = pressure.coerceIn(0f, 1f)
        return (minWidth + (maxWidth - minWidth) * (if (squared) p * p else p)) * scale
    }

    /**
     * The width to give Onyx's fountain pen for the live line at [pxPerPt]. Its SDK draws a point
     * 2 + (W + 1) × pressure² pixels wide for a width W, so the line grows with pressure as the
     * saved ink does, at any zoom; on the panel it comes out about a fifth wider than that.
     */
    fun liveFountainWidth(pxPerPt: Float): Float = (liveSpan * pxPerPt - 1f).coerceAtLeast(1f)

    companion object {
        /** 1.2 x 0.8 to 3.2 pt, the SDK's curve for the live line below; measured, see above. */
        private val PEN = StrokeStyle(floatArrayOf(0f, 0f, 0f), opacity = 1f, minWidth = 0.96f, maxWidth = 3.84f, squared = true, liveSpan = 2.4f)

        // Wide and see-through, so the printed text underneath stays readable.
        private val HIGHLIGHTER = StrokeStyle(floatArrayOf(1f, 0.92f, 0.23f), opacity = 0.4f, minWidth = 12f, maxWidth = 12f)

        fun of(kind: StrokeKind): StrokeStyle = when (kind) {
            StrokeKind.PEN -> PEN
            StrokeKind.HIGHLIGHTER -> HIGHLIGHTER
        }
    }
}
