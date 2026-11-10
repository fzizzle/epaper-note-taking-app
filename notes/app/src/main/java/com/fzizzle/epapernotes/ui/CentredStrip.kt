package com.fzizzle.epapernotes.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * The writing strip: [start] at the left edge, [end] at the right, and [middle] (the island,
 * [middleHeight] tall) centred on the screen. If the middle would run into either side, it
 * slides over to stay [gap] clear of it; if it still doesn't fit, it gets only the room left,
 * and its buttons shrink to fit (see [StripRow]).
 */
class CentredStrip(
    context: Context,
    private val gap: Int,
    private val start: View,
    private val middle: View,
    private val end: View,
    private val middleHeight: Int,
) : ViewGroup(context) {

    init {
        addView(start)
        addView(middle)
        addView(end)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val fullHeight = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        start.measure(unbounded, fullHeight)
        end.measure(unbounded, fullHeight)
        val room = (w - start.measuredWidth - end.measuredWidth - 2 * gap).coerceAtLeast(0)
        middle.measure(
            MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(middleHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        start.layout(0, 0, start.measuredWidth, h)
        end.layout(w - end.measuredWidth, 0, w, h)
        val mw = middle.measuredWidth
        val lowest = start.measuredWidth + gap
        val highest = w - end.measuredWidth - gap - mw
        val x = ((w - mw) / 2).coerceAtMost(highest).coerceAtLeast(lowest)
        val y = (h - middle.measuredHeight) / 2
        middle.layout(x, y, x + mw, y + middle.measuredHeight)
    }
}
