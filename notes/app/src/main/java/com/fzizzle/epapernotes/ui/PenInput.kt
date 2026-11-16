package com.fzizzle.epapernotes.ui

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokeStyle
import com.fzizzle.epapernotes.tools.LiveLine
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * The pen, through Boox's fast-ink mode: Onyx draws the ink straight onto the e-ink panel as
 * the pen moves, which is what makes it feel immediate (spec §5). When the pen lifts, the
 * whole stroke comes back through [onStroke] as `[x, y, rawPressure]` points in [view] pixels.
 * The eraser end (button 2 held, probe results §1) comes back the same way through [onEraseTrail].
 *
 * This is the only place in the app that uses Onyx's SDK.
 *
 * While fast-ink is on, the screen ignores normal drawing. So it's paused while fingers
 * move the page, and while a page is redrawn with saved ink.
 */
class PenInput(
    private val view: View,
    private val onPenDown: () -> Unit,
    private val onStroke: (List<FloatArray>) -> Unit,
    private val onEraseTrail: (List<FloatArray>) -> Unit,
    /** The pen lifted, after drawing or erasing. [isWriting] is already false. */
    private val onPenUp: () -> Unit = {},
) {

    private var helper: TouchHelper? = null

    /**
     * True while Onyx follows the pen on the screen. Nothing should pause fast-ink then: it
     * would cut the stroke short. If something does, the stroke is over ([pause]).
     */
    @Volatile var isWriting = false
        private set
    private var line = LiveLine.PEN
    private var pxPerPt = 1f

    private val handover = StrokeHandover()

    /**
     * True from a pen-down until its points have been handed over through [onStroke] or
     * [onEraseTrail], and those have returned (the stroke is recorded, the erase sent). Onyx
     * reports the lift ([isWriting] goes false) before it hands the points over, so in between
     * the pen is up but the stroke isn't with the app yet. A reload must wait for it: see
     * [StrokeHandover]. UI thread.
     */
    val strokeComing: Boolean get() = handover.coming(SystemClock.uptimeMillis())

    /**
     * True while Onyx draws the pen's ink on the panel: fast-ink is on and not paused. A stroke
     * that lands then is drawn by Onyx, so the app must record it. It errs towards true: set
     * before Onyx starts drawing and cleared only after it has stopped.
     */
    @Volatile var drawsInk = false
        private set

    fun on(limit: Rect) {
        if (helper != null) return
        val h = TouchHelper.create(view, callback)
        h.setLimitRect(limit, emptyList()).openRawDrawing()
        // Button 1 must reach the app as a button, not be turned into erasing by Onyx
        // (probe results §5). Button 2 arrives as the eraser end, which plan 3 makes erase.
        h.enableSideBtnErase(false)
        helper = h
        applyLine()
        drawsInk = true
        h.setRawDrawingEnabled(true)
    }

    fun off() {
        val h = helper ?: return
        h.setRawDrawingEnabled(false)
        drawsInk = false
        isWriting = false // as in pause()
        handover.cut(SystemClock.uptimeMillis())
        h.closeRawDrawing()
        helper = null
    }

    fun pause() {
        val h = helper ?: return
        h.setRawDrawingEnabled(false)
        drawsInk = false
        // Onyx may send no end callback for a stroke this cut short. Left true, the screen
        // would wait for a lift that never comes: no page would show, and no finger would work.
        isWriting = false
        handover.cut(SystemClock.uptimeMillis()) // nor would a reload wait for ever for its points
    }

    fun resume() {
        val h = helper ?: return
        drawsInk = true
        h.setRawDrawingEnabled(true)
    }

    /**
     * A clean e-ink refresh of [view] after the page has zoomed or moved. Fast-ink's own copy
     * of the strokes can stay on the panel while the page moves underneath, showing up as
     * stretched, skewed ghosts (owner's writing session); this wipes them. REGAL, the owner's
     * choice (2026-11-10) over the full GC flash: no ghosts left, and gentler.
     */
    fun cleanRefresh(view: View) {
        EpdController.invalidate(view, AFTER_ZOOM)
    }

    /**
     * Draws [view] again once fast-ink is paused for a new look of the page: a page turn, or
     * the page redrawn after an erase, a move or an undo. Left to itself the page view gets
     * GC, a hard flash; REGAL clears the old page with little flash (owner's choice, 2026-11-10,
     * over GC and GU).
     */
    fun showPage(view: View) {
        EpdController.invalidate(view, PAGE_SHOW)
    }

    /**
     * Puts [view]'s new look on the panel while fast-ink stays on. While fast-ink is on, Onyx
     * stops passing the app's drawing to the panel ("post", for the whole window), which froze
     * the strip; Onyx's handwriting repaint, used before, didn't reliably get it through.
     * Posting is let through for a moment, the strip draws, and posting stops again; the pen
     * keeps working throughout. Opening posting makes the panel refresh the whole screen once
     * (owner, 2026-11-10; an explicit GU update of the strip didn't change that). If fast-ink
     * was paused in between, the pause has let posting through already, and stopping it would
     * freeze the screen.
     */
    fun showAlone(view: View) {
        if (!drawsInk) return view.invalidate()
        EpdController.leaveScribbleMode(view)
        view.invalidate()
        view.postDelayed({ if (drawsInk) EpdController.enterScribbleMode(view) }, POST_OPEN_MS)
    }

    fun setLimit(limit: Rect) {
        helper?.setLimitRect(limit, emptyList())
    }

    /**
     * How Onyx draws while the pen moves. [pxPerPt]: screen pixels per PDF point where the pen
     * is writing, so the live ink matches the saved ink. Hover events call this often, so Onyx
     * is only told when something actually changed.
     */
    fun setLiveLine(line: LiveLine, pxPerPt: Float) {
        if (line == this.line && pxPerPt == this.pxPerPt) return
        this.line = line
        this.pxPerPt = pxPerPt
        applyLine()
    }

    private fun applyLine() {
        val h = helper ?: return
        when (line) {
            LiveLine.PEN -> {
                h.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN) // the line follows pressure
                h.setStrokeColor(Color.BLACK)
                h.setStrokeWidth(StrokeStyle.of(StrokeKind.PEN).liveFountainWidth(pxPerPt)) // as wide as the saved ink
            }
            LiveLine.HIGHLIGHTER -> {
                h.setStrokeStyle(TouchHelper.STROKE_STYLE_MARKER)
                h.setStrokeColor(StripButton.HIGHLIGHTER_YELLOW)
                h.setStrokeWidth(StrokeStyle.of(StrokeKind.HIGHLIGHTER).maxWidth * pxPerPt)
            }
            // The eraser's and the lasso's trail: just where the pen went, at any zoom. Fountain,
            // as for the pen: Onyx's pencil (a textured line) refreshed the whole screen at
            // every switch to the eraser (owner, 2026-11-10). At 2 px pressure makes no difference.
            LiveLine.THIN_GREY -> {
                h.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                h.setStrokeColor(Color.GRAY)
                h.setStrokeWidth(THIN_GREY_PX)
            }
        }
    }

    private val callback = object : RawInputCallback() {
        // Comes after onEndRawDrawing: the stroke is only with the app once this returns.
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            try {
                val points = list?.points ?: return
                onStroke(points.map { floatArrayOf(it.x, it.y, it.pressure) })
            } finally {
                handover.handedOver()
            }
        }

        override fun onBeginRawDrawing(shortcut: Boolean, p: TouchPoint?) {
            handover.begun()
            isWriting = true
            onPenDown()
        }

        override fun onEndRawDrawing(shortcut: Boolean, p: TouchPoint?) {
            handover.lifted(SystemClock.uptimeMillis())
            isWriting = false
            onPenUp()
        }
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint?) = Unit

        // Button 2 (the "eraser end", probe results §1): a pen-down like any other, so the
        // trail is placed with the view as it was when it started.
        override fun onBeginRawErasing(shortcut: Boolean, p: TouchPoint?) {
            handover.begun()
            isWriting = true
            onPenDown()
        }

        override fun onEndRawErasing(shortcut: Boolean, p: TouchPoint?) {
            handover.lifted(SystemClock.uptimeMillis())
            isWriting = false
            onPenUp()
        }
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            try {
                val points = list?.points ?: return
                onEraseTrail(points.map { floatArrayOf(it.x, it.y, it.pressure) })
            } finally {
                handover.handedOver()
            }
        }
    }

    companion object {
        private const val THIN_GREY_PX = 2f

        /** The panel updates the owner chose (readability plan, Task 4). */
        private val PAGE_SHOW = UpdateMode.REGAL
        private val AFTER_ZOOM = UpdateMode.REGAL

        /** How long posting stays open for [showAlone]: the strip's frame, drawn and composed, with room to spare. */
        private const val POST_OPEN_MS = 150L
    }
}
