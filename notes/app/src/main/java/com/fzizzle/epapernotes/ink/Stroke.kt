package com.fzizzle.epapernotes.ink

enum class StrokeKind { PEN, HIGHLIGHTER }

/**
 * One sampled pen position.
 *
 * [x] and [y] are in PDF user space: points, y pointing up, in the page's own unrotated
 * coordinates (the same space as its MediaBox). Keeping strokes in this space is what makes
 * ink turn with the page for free: rotating a page only changes how it's displayed.
 * [pressure] runs from 0 (barely touching) to 1 (full pressure).
 */
data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

/**
 * A single pen or highlighter stroke. Always has at least one point (a tap makes a dot).
 *
 * [widthScale] multiplies the style's width. It is 1 for fresh ink; resizing a selection
 * multiplies it, so enlarged writing keeps its look instead of getting thin lines.
 */
data class Stroke(val kind: StrokeKind, val points: List<StrokePoint>, val widthScale: Float = 1f) {
    init {
        require(points.isNotEmpty()) { "a stroke needs at least one point" }
    }
}
