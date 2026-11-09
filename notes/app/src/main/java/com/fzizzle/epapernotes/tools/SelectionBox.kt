package com.fzizzle.epapernotes.tools

import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.ink.Box
import kotlin.math.max
import kotlin.math.min

/** The box drawn round a selection, in screen pixels (y down), and what dragging it does. */
class SelectionBox(val bounds: Box) {
    enum class Grip { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    fun gripAt(x: Float, y: Float, handlePx: Float): Grip {
        fun near(cx: Float, cy: Float) = Math.abs(x - cx) <= handlePx && Math.abs(y - cy) <= handlePx
        return when {
            near(bounds.x0, bounds.y0) -> Grip.TOP_LEFT
            near(bounds.x1, bounds.y0) -> Grip.TOP_RIGHT
            near(bounds.x0, bounds.y1) -> Grip.BOTTOM_LEFT
            near(bounds.x1, bounds.y1) -> Grip.BOTTOM_RIGHT
            x in bounds.x0..bounds.x1 && y in bounds.y0..bounds.y1 -> Grip.MOVE
            else -> Grip.NONE
        }
    }

    /** The transform (screen pixels) that a drag of [dx],[dy] on [grip] applies to the selection. */
    fun drag(grip: Grip, dx: Float, dy: Float): Affine {
        val w = bounds.x1 - bounds.x0
        val h = bounds.y1 - bounds.y0
        // Which side moves (+1 right/down, -1 left/up) and the opposite corner that stays put.
        val (sx, sy) = when (grip) {
            Grip.MOVE -> return Affine.translate(dx, dy)
            Grip.NONE -> return Affine.IDENTITY
            Grip.TOP_LEFT -> -1f to -1f
            Grip.TOP_RIGHT -> 1f to -1f
            Grip.BOTTOM_LEFT -> -1f to 1f
            Grip.BOTTOM_RIGHT -> 1f to 1f
        }
        val anchorX = if (sx > 0) bounds.x0 else bounds.x1
        val anchorY = if (sy > 0) bounds.y0 else bounds.y1
        val ratio = max(max((w + sx * dx) / w, (h + sy * dy) / h), MIN_SIDE_PX / min(w, h))
        return Affine(ratio, 0f, 0f, ratio, anchorX * (1 - ratio), anchorY * (1 - ratio))
    }

    companion object {
        const val MIN_SIDE_PX = 24f
    }
}
