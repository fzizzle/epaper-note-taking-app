package com.fzizzle.epapernotes.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The top strip's row: square buttons of [side] pixels, [gap] apart, and whatever else is
 * added (the page number). If they don't all fit across the screen, as can happen in portrait,
 * the squares and their gaps shrink together until they do: every button is always on screen,
 * in one row. Text keeps its size.
 */
open class StripRow(context: Context, private val side: Int, private val gap: Int) : LinearLayout(context) {

    /** Each square's gap after it at full size. */
    private val gapAfter = HashMap<View, Int>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(gap, 0, gap, 0)
    }

    /** Adds a square button, followed by [gaps] gaps. */
    open fun addSquare(button: StripButton, gaps: Int = 1) {
        gapAfter[button] = gap * gaps
        // rightMargin, not marginEnd: a relative margin set after layout would be resolved over
        // what sizeSquares sets. The app is left-to-right only.
        addView(button, LayoutParams(side, side).apply { rightMargin = gap * gaps })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val room = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        // The page number at its own width: LinearLayout would give it only what's left over.
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var squares = 0
        var others = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            when {
                child in gapAfter -> squares += side + gapAfter.getValue(child)
                lp.weight == 0f -> {
                    child.measure(unbounded, unbounded)
                    others += child.measuredWidth + lp.marginStart + lp.marginEnd
                }
            }
        }
        val fits = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || squares + others <= room
        sizeSquares(if (fits || squares == 0) 1f else ((room - others).toFloat() / squares).coerceAtLeast(0f))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Sets every square to [scale] of its full size, rounding down so the row can't overflow. */
    private fun sizeSquares(scale: Float) {
        for ((button, after) in gapAfter) {
            val lp = button.layoutParams as LayoutParams
            lp.width = (side * scale).toInt()
            lp.height = lp.width
            lp.rightMargin = (after * scale).toInt()
        }
    }
}
