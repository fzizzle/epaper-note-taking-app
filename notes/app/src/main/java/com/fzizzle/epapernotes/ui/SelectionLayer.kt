package com.fzizzle.epapernotes.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.tools.PenButton
import com.fzizzle.epapernotes.tools.SelectionBox
import kotlin.math.max
import kotlin.math.min

/** Where the page on show is: PDF space to display points to screen pixels, and its size. */
class PageFrame(val pdfToDisplay: Affine, val displayToScreen: Affine, val size: SizePt) {
    val pdfToScreen: Affine get() = pdfToDisplay.then(displayToScreen)
}

/**
 * What the pen does while fast-ink is paused (plan decisions 6 and 8): the selection box with
 * its grips and Copy/Delete bar, the snip rectangle, and the tap that places a paste. It draws
 * them on the page view and works out what a pen point hits.
 *
 * It never changes the document. It tells [listener] what happened, and the writing screen
 * turns that into changes, then hands back the items to show with [select]. UI thread only.
 */
class SelectionLayer(private val handlePx: Float, private val listener: Listener, private val redraw: () -> Unit) {

    interface Listener {
        /** A drag or resize finished. [pdfTransform] is in the page's PDF space. */
        fun moved(pdfTransform: Affine)
        fun copy()
        fun delete()

        /** The pen came down outside the selection: let go of it. */
        fun cleared()

        /** A snip rectangle, in display points of the page (top-left origin), at least [MIN_SNIP_PT] a side. */
        fun snipped(displayBox: Box)

        /** A tap while a paste is armed, in display points of the page. */
        fun pasteAt(at: Pt)

        /** A stroke with a side button held, traced here because fast-ink is off. Screen pixels. */
        fun traced(button: PenButton, points: List<Pt>)
    }

    /** True while the snip tool is chosen. */
    var snipping = false

    /** True while Paste waits for its tap. */
    var pasteArmed = false

    private var items: List<Item> = emptyList()
    private var bounds: Box? = null // PDF space

    // A finished move is shown grey where it went until the page is drawn again with it there.
    private var previewUntilRedrawn = false

    private sealed interface Gesture
    private class Drag(val grip: SelectionBox.Grip, val box: SelectionBox, val frame: PageFrame, val start: Pt) : Gesture {
        var transform = Affine.IDENTITY // screen pixels
    }
    private class Snip(val start: Pt) : Gesture {
        var end = start
    }
    private class Paste(val at: Pt) : Gesture
    private class Trace(val button: PenButton) : Gesture {
        val points = mutableListOf<Pt>()
    }
    private object Swallow : Gesture

    private var gesture: Gesture? = null

    val hasSelection: Boolean get() = items.isNotEmpty()

    /** Shows a selection of [items] (PDF space). [justMoved]: the page doesn't show them there yet. */
    fun select(items: List<Item>, justMoved: Boolean = false) {
        this.items = items
        bounds = union(items)
        previewUntilRedrawn = justMoved && items.isNotEmpty()
        redraw()
    }

    fun clearSelection() {
        items = emptyList()
        bounds = null
        previewUntilRedrawn = false
        if (gesture is Drag) gesture = Swallow
        redraw()
    }

    /** The page was drawn again from the PDF, so a finished move no longer needs its preview. */
    fun pageRedrawn() {
        if (!previewUntilRedrawn) return
        previewUntilRedrawn = false
        redraw()
    }

    /**
     * A pen event while fast-ink is paused. [button] is the one held at touch-down. Events from a
     * stroke that started before this layer took over (no down seen) are ignored.
     */
    fun onPen(event: MotionEvent, button: PenButton, frame: PageFrame, viewWidth: Int): Boolean {
        val here = Pt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = startGesture(here, button, frame, viewWidth)
                redraw()
            }
            MotionEvent.ACTION_MOVE -> when (val g = gesture) {
                is Drag -> {
                    g.transform = g.box.drag(g.grip, here.x - g.start.x, here.y - g.start.y)
                    redraw()
                }
                is Snip -> {
                    g.end = here
                    redraw()
                }
                is Trace -> {
                    for (i in 0 until event.historySize) g.points += Pt(event.getHistoricalX(i), event.getHistoricalY(i))
                    g.points += here
                    redraw()
                }
                else -> Unit
            }
            MotionEvent.ACTION_UP -> finish(gesture, here, frame)
            MotionEvent.ACTION_CANCEL -> {
                gesture = null
                redraw()
            }
        }
        return true
    }

    /** A finger tap: only the Copy and Delete bar answers fingers. */
    fun onFingerTap(x: Float, y: Float, frame: PageFrame, viewWidth: Int): Boolean {
        if (!hasSelection || gesture != null) return false
        return pressBar(x, y, frame, viewWidth)
    }

    private fun startGesture(at: Pt, button: PenButton, frame: PageFrame, viewWidth: Int): Gesture {
        // A side button always does its own thing: button 2 erases, button 1 lassos afresh.
        if (button != PenButton.NONE) {
            if (hasSelection) listener.cleared()
            return Trace(button).also { it.points += at }
        }
        if (pasteArmed) return Paste(at)
        if (snipping) return Snip(at)
        val b = bounds ?: return Swallow
        if (pressBar(at.x, at.y, frame, viewWidth)) return Swallow
        val box = SelectionBox(screenBox(b, frame.pdfToScreen))
        val grip = box.gripAt(at.x, at.y, handlePx)
        if (grip == SelectionBox.Grip.NONE) {
            listener.cleared()
            return Swallow
        }
        return Drag(grip, box, frame, at)
    }

    private fun finish(g: Gesture?, at: Pt, frame: PageFrame) {
        gesture = null
        when (g) {
            is Drag -> if (g.transform != Affine.IDENTITY) {
                // Screen pixels, with the view as it was when the drag started, into PDF space.
                val toScreen = g.frame.pdfToScreen
                listener.moved(toScreen.then(g.transform).then(toScreen.inverse()))
            }
            is Snip -> {
                val toDisplay = frame.displayToScreen.inverse()
                val a = toDisplay.map(g.start)
                val b = toDisplay.map(at)
                val box = Box(
                    min(a.x, b.x).coerceIn(0f, frame.size.w), min(a.y, b.y).coerceIn(0f, frame.size.h),
                    max(a.x, b.x).coerceIn(0f, frame.size.w), max(a.y, b.y).coerceIn(0f, frame.size.h),
                )
                if (box.x1 - box.x0 >= MIN_SNIP_PT && box.y1 - box.y0 >= MIN_SNIP_PT) listener.snipped(box)
            }
            is Paste -> listener.pasteAt(frame.displayToScreen.inverse().map(g.at))
            is Trace -> listener.traced(g.button, g.points.toList())
            Swallow, null -> Unit
        }
        redraw()
    }

    /** Copy or Delete, if [x],[y] is on one of them. */
    private fun pressBar(x: Float, y: Float, frame: PageFrame, viewWidth: Int): Boolean {
        val b = bounds ?: return false
        val (copy, delete) = barRects(screenBox(b, frame.pdfToScreen), viewWidth)
        when {
            copy.contains(x, y) -> listener.copy()
            delete.contains(x, y) -> listener.delete()
            else -> return false
        }
        return true
    }

    /**
     * The bar's two buttons: above the box, or below it if there's no room above. Kept a grip's
     * width clear of the box so a corner drag never lands on them.
     */
    private fun barRects(box: Box, viewWidth: Int): Pair<RectF, RectF> {
        val h = handlePx * 1.8f
        val w = h * 2.6f
        val gap = handlePx
        val top = if (box.y0 - gap - h >= 0f) box.y0 - gap - h else box.y1 + gap
        val left = box.x0.coerceIn(0f, max(0f, viewWidth - 2 * w - gap / 2))
        return RectF(left, top, left + w, top + h) to RectF(left + w + gap / 2, top, left + 2 * w + gap / 2, top + h)
    }

    fun draw(canvas: Canvas, frame: PageFrame) {
        val toScreen = frame.pdfToScreen
        val drag = gesture as? Drag
        bounds?.let { b ->
            val t = drag?.transform ?: Affine.IDENTITY
            val box = screenBox(b, toScreen.then(t))
            if (drag != null || previewUntilRedrawn) items.forEach { drawPreview(canvas, it, toScreen.then(t)) }
            canvas.drawRect(box.x0, box.y0, box.x1, box.y1, dashed)
            val s = handlePx / 2
            for (x in floatArrayOf(box.x0, box.x1)) for (y in floatArrayOf(box.y0, box.y1)) canvas.drawRect(x - s, y - s, x + s, y + s, filled)
            if (drag == null) drawBar(canvas, box, canvas.width)
        }
        when (val g = gesture) {
            is Snip -> canvas.drawRect(min(g.start.x, g.end.x), min(g.start.y, g.end.y), max(g.start.x, g.end.x), max(g.start.y, g.end.y), dashed)
            is Trace -> if (g.points.size > 1) {
                val path = Path()
                path.moveTo(g.points[0].x, g.points[0].y)
                g.points.drop(1).forEach { path.lineTo(it.x, it.y) }
                canvas.drawPath(path, grey)
            }
            else -> Unit
        }
    }

    private fun drawBar(canvas: Canvas, box: Box, viewWidth: Int) {
        val (copy, delete) = barRects(box, viewWidth)
        label.textSize = copy.height() * 0.45f
        for ((r, text) in listOf(copy to "Copy", delete to "Delete")) {
            canvas.drawRect(r, white)
            canvas.drawRect(r, outline)
            canvas.drawText(text, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
        }
    }

    /** Thin grey lines where the ink will go; a picture as its outline. */
    private fun drawPreview(canvas: Canvas, item: Item, pdfToScreen: Affine) {
        val path = Path()
        val pts = when (item) {
            is InkItem -> item.stroke.points.map { pdfToScreen.map(Pt(it.x, it.y)) }
            is ImageItem -> with(item.box) { listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1), Pt(x0, y0)).map { pdfToScreen.map(it) } }
        }
        path.moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { path.lineTo(it.x, it.y) }
        if (pts.size == 1) path.lineTo(pts[0].x + 1f, pts[0].y)
        canvas.drawPath(path, grey)
    }

    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val filled = Paint().apply { color = Color.BLACK }
    private val white = Paint().apply { color = Color.WHITE }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 2f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }
    private val grey = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.GRAY
        strokeWidth = 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    companion object {
        /** A snip smaller than this on a side (display points) is a slip, not a snip. */
        const val MIN_SNIP_PT = 10f

        private fun union(items: List<Item>): Box? {
            if (items.isEmpty()) return null
            return Box(items.minOf { it.box.x0 }, items.minOf { it.box.y0 }, items.maxOf { it.box.x1 }, items.maxOf { it.box.y1 })
        }

        /** A PDF box on screen: the corners can swap under rotation, so take the extremes. */
        private fun screenBox(b: Box, pdfToScreen: Affine): Box {
            val c = listOf(Pt(b.x0, b.y0), Pt(b.x1, b.y0), Pt(b.x0, b.y1), Pt(b.x1, b.y1)).map { pdfToScreen.map(it) }
            return Box(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })
        }
    }
}
