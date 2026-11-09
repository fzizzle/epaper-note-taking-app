package com.fzizzle.epapernotes.ui

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokeStyle
import com.fzizzle.epapernotes.tools.Tool
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
) {

    private var helper: TouchHelper? = null

    /** True while the pen is on the screen. Nothing may pause fast-ink then: it would cut the stroke short. */
    @Volatile var isWriting = false
        private set
    private var tool = Tool.PEN
    private var pxPerPt = 1f

    fun on(limit: Rect) {
        if (helper != null) return
        val h = TouchHelper.create(view, callback)
        h.setLimitRect(limit, emptyList()).openRawDrawing()
        // Button 1 must reach the app as a button, not be turned into erasing by Onyx
        // (probe results §5). Button 2 arrives as the eraser end, which plan 3 makes erase.
        h.enableSideBtnErase(false)
        helper = h
        applyTool()
        h.setRawDrawingEnabled(true)
    }

    fun off() {
        val h = helper ?: return
        h.setRawDrawingEnabled(false)
        h.closeRawDrawing()
        helper = null
    }

    fun pause() {
        helper?.setRawDrawingEnabled(false)
    }

    fun resume() {
        helper?.setRawDrawingEnabled(true)
    }

    /**
     * A full, clean e-ink refresh of [view]. Fast-ink's own copy of the strokes can stay on the
     * panel while the page zooms or moves underneath, showing up as stretched, skewed ghosts
     * (owner's writing session). A full refresh wipes them. It flashes once, so it's only used
     * after the page has moved.
     */
    fun cleanRefresh(view: View) {
        EpdController.invalidate(view, UpdateMode.GC)
    }

    fun setLimit(limit: Rect) {
        helper?.setLimitRect(limit, emptyList())
    }

    /** [pxPerPt]: screen pixels per PDF point where the pen is writing, so the live ink matches the saved ink. */
    fun setTool(tool: Tool, pxPerPt: Float) {
        this.tool = tool
        this.pxPerPt = pxPerPt
        applyTool()
    }

    private fun applyTool() {
        val h = helper ?: return
        when (tool) {
            Tool.HIGHLIGHTER -> {
                h.setStrokeStyle(TouchHelper.STROKE_STYLE_MARKER)
                h.setStrokeColor(StripButton.HIGHLIGHTER_YELLOW)
                h.setStrokeWidth(StrokeStyle.of(StrokeKind.HIGHLIGHTER).maxWidth * pxPerPt)
            }
            else -> { // PEN; Task 7 gives the other tools their own live line
                h.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN) // the line follows pressure
                h.setStrokeColor(Color.BLACK)
                h.setStrokeWidth(StrokeStyle.of(StrokeKind.PEN).maxWidth * pxPerPt)
            }
        }
    }

    private val callback = object : RawInputCallback() {
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            val points = list?.points ?: return
            onStroke(points.map { floatArrayOf(it.x, it.y, it.pressure) })
        }

        override fun onBeginRawDrawing(shortcut: Boolean, p: TouchPoint?) {
            isWriting = true
            onPenDown()
        }

        override fun onEndRawDrawing(shortcut: Boolean, p: TouchPoint?) {
            isWriting = false
        }
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint?) = Unit

        // Button 2 (the "eraser end", probe results §1). Ignored until plan 3 adds the eraser.
        override fun onBeginRawErasing(shortcut: Boolean, p: TouchPoint?) {
            isWriting = true
        }

        override fun onEndRawErasing(shortcut: Boolean, p: TouchPoint?) {
            isWriting = false
        }
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) = Unit
    }
}
