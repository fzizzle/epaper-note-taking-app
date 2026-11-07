package com.fzizzle.epapernotes.probe

import android.graphics.Rect
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Wraps Boox's fast-ink ("raw drawing") mode on [view]. In this mode Onyx draws ink straight
 * to the e-ink panel and reports strokes through its own callbacks instead of MotionEvents.
 * We log which callback family fires (draw vs erase), because Onyx may route a pen side
 * button into "raw erasing".
 */
class OnyxInput(private val view: View, private val log: ProbeLog) {

    private var helper: TouchHelper? = null
    var isOn = false
        private set

    fun turnOn() {
        if (isOn) return
        val limit = Rect(0, 0, view.width, view.height)
        val h = TouchHelper.create(view, callback)
        h.setStrokeWidth(3f).setLimitRect(limit, emptyList()).openRawDrawing()
        h.setRawDrawingEnabled(true)
        helper = h
        isOn = true
        log.note("fast-ink ON (limit=$limit)")
    }

    fun turnOff() {
        val h = helper ?: return
        h.setRawDrawingEnabled(false)
        h.closeRawDrawing()
        helper = null
        isOn = false
        log.note("fast-ink OFF")
    }

    private fun event(source: EventSource, action: String, p: TouchPoint?) = log.add(
        ProbeEvent(
            source = source,
            fastInk = true,
            toolType = -1,
            action = action,
            buttonState = -1,
            actionButton = -1,
            keyCode = -1,
            x = p?.x ?: -1f,
            y = p?.y ?: -1f,
            pressure = p?.pressure ?: 0f,
        ),
    )

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint?) = event(EventSource.ONYX_DRAW, "BEGIN", p)
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint?) = event(EventSource.ONYX_DRAW, "END", p)
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            log.note("ONYX_DRAW list n=${list?.points?.size ?: 0}")
        }

        override fun onBeginRawErasing(b: Boolean, p: TouchPoint?) = event(EventSource.ONYX_ERASE, "BEGIN", p)
        override fun onEndRawErasing(b: Boolean, p: TouchPoint?) = event(EventSource.ONYX_ERASE, "END", p)
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            log.note("ONYX_ERASE list n=${list?.points?.size ?: 0}")
        }
    }
}
