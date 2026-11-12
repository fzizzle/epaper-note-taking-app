package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * The line under the writing strip, which also says where the owner is: black from the left
 * as far as the page on show, grey after it, with a small black tick hanging under it for
 * each page that has writing on it. It takes [HEIGHT_PX] of the screen, and only changes when
 * the page does or a page gets its first writing.
 */
class ProgressRule(context: Context) : View(context) {
    private var page = 0
    private var pageCount = 0
    private var written: Set<Int> = emptySet()
    private val paint = Paint()

    /** Redraws only when something it shows changed. */
    fun show(page: Int, pageCount: Int, written: Set<Int>) {
        if (page == this.page && pageCount == this.pageCount && written == this.written) return
        this.page = page
        this.pageCount = pageCount
        this.written = written.toSet() // its own copy: the caller's set goes on changing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val reached = ProgressMarks.reachedPx(page, pageCount, w).toFloat()
        paint.color = RULE
        canvas.drawRect(reached, 0f, w.toFloat(), LINE_PX, paint)
        paint.color = Color.BLACK
        canvas.drawRect(0f, 0f, reached, LINE_PX, paint)
        for (p in written) {
            if (p !in 0 until pageCount) continue
            val x = ProgressMarks.tickPx(p, pageCount, w) - TICK_WIDTH_PX / 2
            canvas.drawRect(x, LINE_PX - TICK_OVERLAP_PX, x + TICK_WIDTH_PX, HEIGHT_PX.toFloat(), paint)
        }
    }

    companion object {
        /** The whole view: the line, and the ticks' room under it. */
        const val HEIGHT_PX = 8
        private const val LINE_PX = 4f
        private const val TICK_WIDTH_PX = 3f
        /** A tick starts this far up inside the line, so it joins it on the grey part too. */
        private const val TICK_OVERLAP_PX = 2f
        private val RULE = Color.rgb(200, 200, 200)
    }
}
