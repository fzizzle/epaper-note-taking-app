package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * One button in the top strip, drawn with a few plain shapes: no image files, no fonts, no
 * ripples or animation. On e-ink, motion only causes flashing, and every extra layer costs
 * time.
 *
 * A tool button shows the mark its tool makes: the pen a stroke that thins out, the
 * highlighter a short yellow band, the only colour in the strip. The selected tool, and any
 * button while it's pressed, is drawn inverted (black tile, white mark), which reads at a
 * glance on e-ink.
 */
class StripButton(context: Context, private val glyph: Glyph, description: String) : View(context) {

    enum class Glyph { BACK, PREVIOUS, NEXT, PEN, HIGHLIGHTER }

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
    }

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    private val isTool get() = glyph == Glyph.PEN || glyph == Glyph.HIGHLIGHTER

    override fun setSelected(selected: Boolean) {
        if (selected != isSelected) super.setSelected(selected)
        invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate() // pressed on, pressed off
    }

    /** The pen's mark: a wave that starts heavy and thins out, like a pressed pen lifting away. Built once per size. */
    private val penMark = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (glyph != Glyph.PEN) return
        val s = minOf(w, h).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        // Centre line: one S-shaped curve across the tile.
        fun point(t: Float): Pair<Float, Float> {
            val x = cx + s * (-0.3f + 0.6f * t)
            val y = cy + s * 0.13f * kotlin.math.sin(t * 2f * Math.PI.toFloat() * 0.85f + 0.6f)
            return x to y
        }
        val steps = 24
        val left = ArrayList<Pair<Float, Float>>()
        val right = ArrayList<Pair<Float, Float>>()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val (x, y) = point(t)
            val (x2, y2) = point(minOf(1f, t + 0.01f))
            val (x1, y1) = point(maxOf(0f, t - 0.01f))
            val len = kotlin.math.hypot(x2 - x1, y2 - y1)
            val nx = -(y2 - y1) / len
            val ny = (x2 - x1) / len
            val half = s * (0.075f * (1f - t) + 0.008f) // heavy at the start, fine at the end
            left += (x + nx * half) to (y + ny * half)
            right += (x - nx * half) to (y - ny * half)
        }
        penMark.reset()
        penMark.moveTo(left[0].first, left[0].second)
        left.drop(1).forEach { penMark.lineTo(it.first, it.second) }
        right.reversed().forEach { penMark.lineTo(it.first, it.second) }
        penMark.close()
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled != isEnabled) super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inverted = isEnabled && (isSelected || isPressed)
        // Greyed out when there's nowhere to go (‹ on the first page, › on the last).
        val fg = when {
            !isEnabled -> DISABLED
            inverted -> Color.WHITE
            else -> Color.BLACK
        }
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f

        if (inverted) canvas.drawColor(Color.BLACK)
        if (isTool && !inverted) {
            // A thin frame marks a tool you can pick; arrows don't need one.
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = 2f
            ink.color = Color.BLACK
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, ink)
        }

        ink.color = fg
        when (glyph) {
            Glyph.BACK -> {
                // ←: a shaft and a head.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.07f
                path.reset()
                path.moveTo(cx + s * 0.22f, cy)
                path.lineTo(cx - s * 0.22f, cy)
                path.moveTo(cx - s * 0.06f, cy - s * 0.16f)
                path.lineTo(cx - s * 0.22f, cy)
                path.lineTo(cx - s * 0.06f, cy + s * 0.16f)
                canvas.drawPath(path, ink)
            }
            Glyph.PREVIOUS, Glyph.NEXT -> {
                val d = if (glyph == Glyph.NEXT) 1 else -1
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.07f
                path.reset()
                path.moveTo(cx - d * s * 0.08f, cy - s * 0.2f)
                path.lineTo(cx + d * s * 0.1f, cy)
                path.lineTo(cx - d * s * 0.08f, cy + s * 0.2f)
                canvas.drawPath(path, ink)
            }
            Glyph.PEN -> {
                ink.style = Paint.Style.FILL
                canvas.drawPath(penMark, ink)
            }
            Glyph.HIGHLIGHTER -> {
                // A short band with a chisel-cut end, in the highlighter's own yellow.
                path.reset()
                path.moveTo(cx - s * 0.3f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.22f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.3f, cy + s * 0.1f)
                path.lineTo(cx - s * 0.3f, cy + s * 0.1f)
                path.close()
                ink.style = Paint.Style.FILL
                ink.color = HIGHLIGHTER_YELLOW
                canvas.drawPath(path, ink)
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = 2f
                ink.color = fg
                canvas.drawPath(path, ink)
            }
        }
    }

    companion object {
        private val DISABLED = Color.rgb(200, 200, 200)

        /** The same yellow the highlighter draws with in fast-ink (PenInput). */
        val HIGHLIGHTER_YELLOW = Color.rgb(255, 235, 59)
    }
}
