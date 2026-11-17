package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
 * glance on e-ink. The eraser, lasso and snip show what they leave or take: a tilted block, a
 * dashed loop, a dashed rectangle. Paste is inverted too while it's armed. The library's top bar
 * reuses the strip with search (a magnifier), refresh (a circling arrow) and new folder (a folder
 * with a plus). On
 * the last page › becomes a page with a plus: add a page after it.
 *
 * On the writing strip's island ([onIsland] set) there are no frames: the outlined capsule groups
 * the buttons, and the selected one sits in a black circle with a white mark.
 */
class StripButton(context: Context, glyph: Glyph, description: String) : View(context) {

    enum class Glyph { BACK, PREVIOUS, NEXT, PEN, HIGHLIGHTER, ERASER, LASSO, SNIP, UNDO, REDO, PASTE, MENU, SEARCH, REFRESH, NEW_FOLDER, LIST, TILES, FIT, ADD_PAGE }

    /** The mark drawn. A button that switches between two things shows the other one's mark. */
    var glyph: Glyph = glyph
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

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

    private val isTool get() = glyph in TOOLS

    /** Whether this button sits on the writing strip's island rather than being a plain strip button. */
    var onIsland = false
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    /** Dashes for the lasso and snip marks, sized to the tile. */
    private var dashes: DashPathEffect? = null

    // Only on a real change: undo and redo are set after every stroke, and a needless redraw
    // costs an e-ink refresh of the strip.
    override fun setSelected(selected: Boolean) {
        if (selected == isSelected) return
        super.setSelected(selected)
        invalidate()
    }

    /**
     * Whether a press is drawn (inverted while held). Off on the writing strip: fast-ink keeps
     * it off the panel anyway, and each redraw there costs a full-screen e-ink update.
     */
    var showsPress = true

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (showsPress) invalidate() // pressed on, pressed off
    }

    /** The pen's mark: a wave that starts heavy and thins out, like a pressed pen lifting away. Built once per size. */
    private val penMark = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = minOf(w, h).toFloat()
        dashes = DashPathEffect(floatArrayOf(s * 0.07f, s * 0.05f), 0f)
        if (glyph != Glyph.PEN) return
        val cx = w / 2f
        val cy = h / 2f
        // Centre line: one S-shaped curve across the tile.
        fun point(t: Float): Pair<Float, Float> {
            // Kept inside the 0.4 circle that picks the pen out (UI review, finding 9).
            val x = cx + s * (-0.24f + 0.48f * t)
            val y = cy + s * 0.11f * kotlin.math.sin(t * 2f * Math.PI.toFloat() * 0.85f + 0.6f)
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
        if (enabled == isEnabled) return
        super.setEnabled(enabled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inverted = isEnabled && (isSelected || (isPressed && showsPress))
        // Greyed out when there's nowhere to go (‹ on the first page, › on the last, undo with nothing to undo).
        val fg = when {
            !isEnabled -> DISABLED
            inverted -> Color.WHITE
            else -> Color.BLACK
        }
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f

        if (inverted && onIsland) {
            // The circle sits just inside the capsule, the same distance from its top and bottom.
            ink.style = Paint.Style.FILL
            ink.color = Color.BLACK
            canvas.drawCircle(cx, cy, s * 0.4f, ink)
        } else if (inverted) {
            canvas.drawColor(Color.BLACK)
        }
        if (isTool && !inverted && !onIsland) {
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
            Glyph.ERASER -> {
                // A block tilted like an eraser at work, its rubber end filled.
                canvas.save()
                canvas.rotate(-35f, cx, cy)
                ink.style = Paint.Style.FILL
                canvas.drawRect(cx - s * 0.3f, cy - s * 0.12f, cx - s * 0.08f, cy + s * 0.12f, ink)
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.05f
                canvas.drawRect(cx - s * 0.3f, cy - s * 0.12f, cx + s * 0.3f, cy + s * 0.12f, ink)
                canvas.restore()
            }
            Glyph.LASSO -> {
                // A dashed loop with a short solid tail where the pen closed it.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.05f
                ink.pathEffect = dashes
                canvas.drawOval(cx - s * 0.3f, cy - s * 0.24f, cx + s * 0.3f, cy + s * 0.12f, ink)
                ink.pathEffect = null
                path.reset()
                path.moveTo(cx - s * 0.16f, cy + s * 0.09f)
                path.quadTo(cx - s * 0.2f, cy + s * 0.2f, cx - s * 0.1f, cy + s * 0.28f)
                canvas.drawPath(path, ink)
            }
            Glyph.SNIP -> {
                // A dashed rectangle, with a solid corner where the drag ends.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.05f
                ink.pathEffect = dashes
                canvas.drawRect(cx - s * 0.28f, cy - s * 0.22f, cx + s * 0.22f, cy + s * 0.18f, ink)
                ink.pathEffect = null
                ink.strokeWidth = s * 0.07f
                path.reset()
                path.moveTo(cx + s * 0.32f, cy + s * 0.04f)
                path.lineTo(cx + s * 0.32f, cy + s * 0.28f)
                path.lineTo(cx + s * 0.08f, cy + s * 0.28f)
                canvas.drawPath(path, ink)
            }
            Glyph.UNDO, Glyph.REDO -> {
                // A U-turn arrow pointing back (undo) or, mirrored, forward (redo).
                canvas.save()
                if (glyph == Glyph.REDO) canvas.scale(-1f, 1f, cx, cy)
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.07f
                val r = s * 0.15f
                path.reset()
                path.moveTo(cx - s * 0.12f, cy + s * 0.22f)
                path.lineTo(cx + s * 0.06f, cy + s * 0.22f)
                path.arcTo(cx + s * 0.06f - r, cy - s * 0.08f, cx + s * 0.06f + r, cy + s * 0.22f, 90f, -180f, false)
                path.lineTo(cx - s * 0.26f, cy - s * 0.08f)
                path.moveTo(cx - s * 0.13f, cy - s * 0.21f)
                path.lineTo(cx - s * 0.26f, cy - s * 0.08f)
                path.lineTo(cx - s * 0.13f, cy + s * 0.05f)
                canvas.drawPath(path, ink)
                canvas.restore()
            }
            Glyph.PASTE -> {
                // A clipboard: the board, and the clip at its top.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.05f
                canvas.drawRect(cx - s * 0.2f, cy - s * 0.22f, cx + s * 0.2f, cy + s * 0.28f, ink)
                ink.style = Paint.Style.FILL
                canvas.drawRect(cx - s * 0.1f, cy - s * 0.3f, cx + s * 0.1f, cy - s * 0.15f, ink)
            }
            Glyph.MENU -> {
                // ⋯
                ink.style = Paint.Style.FILL
                for (i in -1..1) canvas.drawCircle(cx + i * s * 0.2f, cy, s * 0.055f, ink)
            }
            Glyph.SEARCH -> {
                // A magnifier: a ring up and to the left, and a short handle down to the right.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.07f
                val r = s * 0.15f
                val gx = cx - s * 0.06f
                val gy = cy - s * 0.06f
                canvas.drawCircle(gx, gy, r, ink)
                val edge = r * 0.7071f // where the ring meets the handle, at 45°
                ink.strokeWidth = s * 0.09f
                canvas.drawLine(gx + edge, gy + edge, cx + s * 0.25f, cy + s * 0.25f, ink)
            }
            Glyph.REFRESH -> {
                // A circle with a gap at the top, and an arrowhead where it ends, pointing on round.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.07f
                val r = s * 0.2f
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -60f, 285f, false, ink)
                // The end of the arc at -135°, and the way it travels there (clockwise on screen).
                val end = Math.toRadians(-135.0)
                val ex = cx + r * kotlin.math.cos(end).toFloat()
                val ey = cy + r * kotlin.math.sin(end).toFloat()
                val tx = -kotlin.math.sin(end).toFloat()
                val ty = kotlin.math.cos(end).toFloat()
                val h = s * 0.12f
                path.reset()
                path.moveTo(ex + tx * h, ey + ty * h)
                path.lineTo(ex - ty * h, ey + tx * h)
                path.lineTo(ex + ty * h, ey - tx * h)
                path.close()
                ink.style = Paint.Style.FILL
                canvas.drawPath(path, ink)
            }
            Glyph.FIT -> {
                // Four corner marks pointing outwards: back to the whole page.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.06f
                val a = s * 0.26f
                val l = s * 0.14f
                for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                    val x = cx + dx * a
                    val y = cy + dy * a
                    canvas.drawLine(x, y, x - dx * l, y, ink)
                    canvas.drawLine(x, y, x, y - dy * l, ink)
                }
            }
            Glyph.LIST -> {
                // Three lines, each after a dot: a list.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.06f
                for (i in -1..1) {
                    val y = cy + i * s * 0.17f
                    canvas.drawLine(cx - s * 0.12f, y, cx + s * 0.26f, y, ink)
                    canvas.drawPoint(cx - s * 0.25f, y, ink)
                }
            }
            Glyph.TILES -> {
                // Four squares: tiles.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.05f
                val a = s * 0.2f
                val g = s * 0.04f
                canvas.drawRect(cx - a - g, cy - a - g, cx - g, cy - g, ink)
                canvas.drawRect(cx + g, cy - a - g, cx + a + g, cy - g, ink)
                canvas.drawRect(cx - a - g, cy + g, cx - g, cy + a + g, ink)
                canvas.drawRect(cx + g, cy + g, cx + a + g, cy + a + g, ink)
            }
            Glyph.ADD_PAGE -> {
                // A page standing upright, with a plus inside: a new page after this one.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.05f
                canvas.drawRect(cx - s * 0.2f, cy - s * 0.28f, cx + s * 0.2f, cy + s * 0.28f, ink)
                canvas.drawLine(cx - s * 0.1f, cy, cx + s * 0.1f, cy, ink)
                canvas.drawLine(cx, cy - s * 0.1f, cx, cy + s * 0.1f, ink)
            }
            Glyph.NEW_FOLDER -> {
                // A folder: its body and the tab on top, with a plus inside.
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = s * 0.05f
                path.reset()
                path.moveTo(cx - s * 0.3f, cy - s * 0.15f)
                path.lineTo(cx - s * 0.3f, cy - s * 0.24f)
                path.lineTo(cx - s * 0.08f, cy - s * 0.24f)
                path.lineTo(cx - s * 0.02f, cy - s * 0.15f)
                path.lineTo(cx + s * 0.3f, cy - s * 0.15f)
                path.lineTo(cx + s * 0.3f, cy + s * 0.25f)
                path.lineTo(cx - s * 0.3f, cy + s * 0.25f)
                path.close()
                canvas.drawPath(path, ink)
                val py = cy + s * 0.05f
                canvas.drawLine(cx - s * 0.1f, py, cx + s * 0.1f, py, ink)
                canvas.drawLine(cx, py - s * 0.1f, cx, py + s * 0.1f, ink)
            }
        }
    }

    companion object {
        private val DISABLED = Color.rgb(200, 200, 200)

        /** The buttons that pick a tool: framed, and inverted while picked. */
        private val TOOLS = setOf(Glyph.PEN, Glyph.HIGHLIGHTER, Glyph.ERASER, Glyph.LASSO, Glyph.SNIP)

        /** The same yellow the highlighter draws with in fast-ink (PenInput). */
        val HIGHLIGHTER_YELLOW = Color.rgb(255, 235, 59)
    }
}
