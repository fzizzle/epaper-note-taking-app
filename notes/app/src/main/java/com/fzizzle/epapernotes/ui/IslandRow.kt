package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout

/**
 * The writing strip's island: the tools, then undo and redo, on one white capsule with a black
 * outline, drawn as a single rounded shape behind its buttons. The picked tool is a black circle
 * with a white mark. No shadow, since on e-ink a soft shadow is only grainy grey. (A black capsule
 * was also tried; the owner chose the outlined one, 2026-11-10.)
 */
class IslandRow(context: Context, side: Int, gap: Int) : StripRow(context, side, gap) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = OUTLINE_WIDTH
        color = Color.BLACK
    }

    init {
        setWillNotDraw(false)
        // The buttons' circles sit as far from the capsule's ends as from its top and bottom.
        setPadding(gap / 2, 0, gap / 2, 0)
    }

    override fun addSquare(button: StripButton, gaps: Int) {
        button.onIsland = true
        super.addSquare(button, gaps)
    }

    /** A short upright line between groups of buttons, [gap] clear on either side. */
    fun addDivider(height: Int, gap: Int) {
        val line = View(context).apply { setBackgroundColor(DIVIDER_GREY) }
        addView(line, LayoutParams(DIVIDER_WIDTH, height).apply { leftMargin = gap; rightMargin = gap })
    }

    override fun onDraw(canvas: Canvas) {
        val w = OUTLINE_WIDTH
        val r = height / 2f
        canvas.drawRoundRect(w / 2, w / 2, width - w / 2, height - w / 2, r - w / 2, r - w / 2, paint)
    }

    private companion object {
        const val DIVIDER_WIDTH = 2
        const val OUTLINE_WIDTH = 3f
        val DIVIDER_GREY = Color.rgb(200, 200, 200)
    }
}
