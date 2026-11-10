package com.fzizzle.epapernotes.ink

/**
 * How a kind of stroke looks: colour, see-through-ness and how width follows pressure.
 * Widths are in PDF points (1/72 inch).
 *
 * The pen follows pressure squared, as Onyx's fountain pen draws its live line (measured on the
 * tablet, FountainWidthCheck, 2026-11-10), so a stroke keeps its width when the app's copy
 * replaces the live line. With a straight line the two matched only for light strokes, and firm
 * ones came out up to 40% thinner once saved (owner, 2026-11-10).
 */
class StrokeStyle(
    val rgb: FloatArray,
    val opacity: Float,
    val minWidth: Float,
    val maxWidth: Float,
    private val squared: Boolean = false,
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
     * The width to give Onyx's fountain pen for the live line at [pxPerPt]. It draws a point
     * 2 + (W + 1) × pressure² pixels wide for a width W, at any speed, so this W makes the line
     * grow with pressure exactly as the saved ink does, at any zoom. Only its 2 px at no pressure
     * doesn't scale: [minWidth] matches it at 2.5 px per point, within half a pixel at normal size.
     */
    fun liveFountainWidth(pxPerPt: Float): Float = ((maxWidth - minWidth) * pxPerPt - 1f).coerceAtLeast(1f)

    companion object {
        private val PEN = StrokeStyle(floatArrayOf(0f, 0f, 0f), opacity = 1f, minWidth = 0.8f, maxWidth = 3.2f, squared = true)

        // Wide and see-through, so the printed text underneath stays readable.
        private val HIGHLIGHTER = StrokeStyle(floatArrayOf(1f, 0.92f, 0.23f), opacity = 0.4f, minWidth = 12f, maxWidth = 12f)

        fun of(kind: StrokeKind): StrokeStyle = when (kind) {
            StrokeKind.PEN -> PEN
            StrokeKind.HIGHLIGHTER -> HIGHLIGHTER
        }
    }
}
