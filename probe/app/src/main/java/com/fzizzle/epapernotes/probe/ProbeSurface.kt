package com.fzizzle.epapernotes.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * The area the owner writes on. It reports every Android pointer event (touch, hover and
 * generic motion) to [onEvent], and with fast-ink off it draws a plain line under the
 * stylus so writing visibly works. With fast-ink on, Onyx draws instead, but this view
 * keeps reporting whatever Android events still reach it: whether any do is a result.
 */
class ProbeSurface(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var onEvent: (ProbeEvent) -> Unit = {}
    var fastInk = false

    private var ink: Bitmap? = null
    private var lastX = 0f
    private var lastY = 0f
    private val pen = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    init {
        holder.addCallback(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        report(EventSource.TOUCH, event)
        if (!fastInk) drawStylus(event)
        // Returning true keeps the rest of the gesture coming to us.
        return true
    }

    // Hover events (pen near the screen, not touching) arrive here, not in onTouchEvent.
    // Many pens only report side-button state while hovering, so this matters.
    override fun onHoverEvent(event: MotionEvent): Boolean {
        report(EventSource.HOVER, event)
        return true
    }

    // Catches BUTTON_PRESS / BUTTON_RELEASE and anything else not routed as touch or hover.
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        report(EventSource.HOVER, event)
        return true
    }

    private fun report(source: EventSource, e: MotionEvent) {
        val i = e.actionIndex
        // Several pointers at once (palm + pen, two fingers) are worth seeing in the log.
        val action = MotionEvent.actionToString(e.actionMasked) + if (e.pointerCount > 1) " n=${e.pointerCount}" else ""
        onEvent(
            ProbeEvent(
                source = source,
                fastInk = fastInk,
                toolType = e.getToolType(i),
                action = action,
                buttonState = e.buttonState,
                actionButton = e.actionButton,
                keyCode = -1,
                x = e.getX(i),
                y = e.getY(i),
                pressure = e.getPressure(i),
            ),
        )
    }

    private fun drawStylus(e: MotionEvent) {
        val tool = e.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
        val bitmap = ink ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val c = Canvas(bitmap)
                c.drawLine(lastX, lastY, e.x, e.y, pen)
                lastX = e.x
                lastY = e.y
                show()
            }
        }
    }

    fun clearInk() {
        ink?.eraseColor(Color.WHITE)
        show()
    }

    private fun show() {
        val bitmap = ink ?: return
        val canvas = holder.lockCanvas() ?: return
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        holder.unlockCanvasAndPost(canvas)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        ink = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        show()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ink = null
    }
}
