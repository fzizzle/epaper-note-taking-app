package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout

/** How the island and the buttons on it are drawn. */
enum class IslandStyle {
    /** A solid black capsule with white marks; the picked tool is a white circle with a black mark. */
    BLACK,

    /** A white capsule with a black outline; the picked tool is a black circle with a white mark. */
    OUTLINE,
}

/**
 * The writing strip's island: the tools, then undo and redo, on one capsule drawn as a single
 * rounded shape behind its buttons. No shadow, since on e-ink a soft shadow is only grainy grey.
 * Buttons put on it with [addSquare] take its [style].
 */
class IslandRow(context: Context, side: Int, gap: Int) : StripRow(context, side, gap) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividers = ArrayList<View>()

    var style = IslandStyle.BLACK
        set(value) {
            if (value == field) return
            field = value
            restyle()
        }

    init {
        setWillNotDraw(false)
        // The buttons' circles sit as far from the capsule's ends as from its top and bottom.
        setPadding(gap / 2, 0, gap / 2, 0)
    }

    override fun addSquare(button: StripButton, gaps: Int) {
        button.island = style
        super.addSquare(button, gaps)
    }

    /** A short upright line between groups of buttons, [gap] clear on either side. */
    fun addDivider(height: Int, gap: Int) {
        val line = View(context)
        dividers += line
        addView(line, LayoutParams(DIVIDER_WIDTH, height).apply { leftMargin = gap; rightMargin = gap })
        restyle()
    }

    private fun restyle() {
        for (i in 0 until childCount) (getChildAt(i) as? StripButton)?.island = style
        val line = if (style == IslandStyle.BLACK) ON_BLACK_GREY else Color.rgb(200, 200, 200)
        dividers.forEach { it.setBackgroundColor(line) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        when (style) {
            IslandStyle.BLACK -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, paint)
            }
            IslandStyle.OUTLINE -> {
                val w = OUTLINE_WIDTH
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w
                paint.color = Color.BLACK
                canvas.drawRoundRect(w / 2, w / 2, width - w / 2, h - w / 2, r - w / 2, r - w / 2, paint)
            }
        }
    }

    companion object {
        private const val DIVIDER_WIDTH = 2
        private const val OUTLINE_WIDTH = 3f

        /** Grey for dividers and greyed-out marks on the black capsule. */
        val ON_BLACK_GREY = Color.rgb(110, 110, 110)
    }
}
