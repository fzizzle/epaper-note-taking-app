package com.fzizzle.epapernotes.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * The writing strip: [start] at the left edge, [end] at the right, and [middle] (the island,
 * [middleHeight] tall) centred on the screen. If the middle would run into either side, it
 * slides over to stay [gap] clear of it; if it still doesn't fit, it gets only the room left,
 * and its buttons shrink to fit (see [StripRow]).
 *
 * [title] (the open file's name) sits just right of [start] and gets only the room left of the
 * island: it never moves the island, it's cut instead (UI review, decision 2).
 */
class CentredStrip(
    context: Context,
    private val gap: Int,
    private val start: View,
    private val middle: View,
    private val end: View,
    private val middleHeight: Int,
    private val title: View? = null,
) : ViewGroup(context) {

    private var placement = Placement(0, 0)

    init {
        addView(start)
        title?.let(::addView)
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
        val titleHeight = MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
        title?.measure(unbounded, titleHeight)
        val wanted = start.measuredWidth + (title?.measuredWidth ?: 0)
        placement = StripPlacement.place(w, start.measuredWidth, wanted, middle.measuredWidth, end.measuredWidth, gap)
        title?.measure(MeasureSpec.makeMeasureSpec(placement.startWidth - start.measuredWidth, MeasureSpec.AT_MOST), titleHeight)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        start.layout(0, 0, start.measuredWidth, h)
        title?.let {
            val top = (h - it.measuredHeight) / 2
            it.layout(start.measuredWidth, top, start.measuredWidth + it.measuredWidth, top + it.measuredHeight)
        }
        end.layout(w - end.measuredWidth, 0, w, h)
        val x = placement.middleX
        val y = (h - middle.measuredHeight) / 2
        middle.layout(x, y, x + middle.measuredWidth, y + middle.measuredHeight)
    }
}

/** Where the island goes ([middleX]) and how wide the left side may be, title and all ([startWidth]). */
data class Placement(val middleX: Int, val startWidth: Int)

object StripPlacement {
    /**
     * The island ([middle] wide) centred on a strip [width] across, moved only to stay [gap]
     * clear of the left side's fixed part ([startMin]) and of [end]. The left side may then be
     * as wide as it wants ([startWanted]) up to [gap] short of the island, and never narrower
     * than [startMin].
     */
    fun place(width: Int, startMin: Int, startWanted: Int, middle: Int, end: Int, gap: Int): Placement {
        val lowest = startMin + gap
        val highest = width - end - gap - middle
        val x = ((width - middle) / 2).coerceAtMost(highest).coerceAtLeast(lowest)
        return Placement(x, minOf(startWanted, x - gap).coerceAtLeast(startMin))
    }
}
