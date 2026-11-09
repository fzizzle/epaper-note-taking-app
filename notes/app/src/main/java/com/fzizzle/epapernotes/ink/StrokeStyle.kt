package com.fzizzle.epapernotes.ink

/**
 * How a kind of stroke looks: colour, see-through-ness and how width follows pressure.
 * Widths are in PDF points (1/72 inch).
 *
 * These are starting values. The pressure curve gets tuned on the tablet once the pen
 * works (plan 2).
 */
class StrokeStyle(val rgb: FloatArray, val opacity: Float, val minWidth: Float, val maxWidth: Float) {

    /**
     * Width at a point: straight-line between [minWidth] (no pressure) and [maxWidth] (full),
     * times [scale] (the stroke's `widthScale`).
     */
    fun widthAt(pressure: Float, scale: Float = 1f): Float =
        (minWidth + (maxWidth - minWidth) * pressure.coerceIn(0f, 1f)) * scale

    companion object {
        private val PEN = StrokeStyle(floatArrayOf(0f, 0f, 0f), opacity = 1f, minWidth = 0.6f, maxWidth = 2.0f)

        // Wide and see-through, so the printed text underneath stays readable.
        private val HIGHLIGHTER = StrokeStyle(floatArrayOf(1f, 0.92f, 0.23f), opacity = 0.4f, minWidth = 12f, maxWidth = 12f)

        fun of(kind: StrokeKind): StrokeStyle = when (kind) {
            StrokeKind.PEN -> PEN
            StrokeKind.HIGHLIGHTER -> HIGHLIGHTER
        }
    }
}
