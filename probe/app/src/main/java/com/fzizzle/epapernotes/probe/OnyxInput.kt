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

    /**
     * Whether the SDK should treat a pen side button as "erase". Its default is unknown, so the
     * probe sets it explicitly and lets the owner run the button tests both ways.
     */
    var sideButtonErase = true

    fun turnOn() {
        if (isOn) return
        val limit = Rect(0, 0, view.width, view.height)
        val h = TouchHelper.create(view, callback)
        h.setStrokeWidth(3f).setLimitRect(limit, emptyList()).openRawDrawing()
        h.enableSideBtnErase(sideButtonErase)
        h.setRawDrawingEnabled(true)
        helper = h
        isOn = true
        log.note("fast-ink ON (limit=$limit sideButtonErase=$sideButtonErase)")
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
        // The Boolean is Onyx's "shortcut" flag: true when a pen button started the stroke.
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint?) = event(EventSource.ONYX_DRAW, onyxAction("BEGIN", b), p)
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint?) = event(EventSource.ONYX_DRAW, onyxAction("END", b), p)
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            log.note("ONYX_DRAW list n=${list?.points?.size ?: 0}")
        }

        override fun onBeginRawErasing(b: Boolean, p: TouchPoint?) = event(EventSource.ONYX_ERASE, onyxAction("BEGIN", b), p)
        override fun onEndRawErasing(b: Boolean, p: TouchPoint?) = event(EventSource.ONYX_ERASE, onyxAction("END", b), p)
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            log.note("ONYX_ERASE list n=${list?.points?.size ?: 0}")
        }
    }
}
