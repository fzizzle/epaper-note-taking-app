package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.fzizzle.epapernotes.geometry.Pt

/**
 * Shows the pages as one long vertical strip and moves it with fingers (spec §6.2): one
 * finger scrolls, two fingers pinch-zoom and pan. The pen is ignored here. While fast-ink is
 * on, Onyx handles the pen itself.
 */
class PageView(context: Context) : View(context) {

    /** Fast-ink must pause while fingers move the page, so these tell the screen when. */
    var onFingerGestureStart: () -> Unit = {}
    var onFingerGestureEnd: () -> Unit = {}
    var onViewportChanged: () -> Unit = {}

    /** Asks for [page] to be drawn [widthPx] wide. The answer comes back through [pageRendered]. */
    var requestRender: (page: Int, widthPx: Int) -> Unit = { _, _ -> }

    var viewport: Viewport? = null
        private set
    private var layout: PageLayout? = null
    private var sizes: List<SizePt> = emptyList()

    private val bitmaps = PageBitmaps<Bitmap>(200L * 1024 * 1024) { it.byteCount.toLong() }
    private val inFlight = mutableSetOf<Pair<Int, Int>>()
    private var pinching = false
    private var fingersDown = false

    private val placeholder = Paint().apply { color = Color.rgb(235, 235, 235) }
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Lays out the document. Call on the UI thread once its page sizes are known. */
    fun setPages(pageSizes: List<SizePt>, startPage: Int) {
        sizes = pageSizes
        if (width == 0) {
            post { setPages(pageSizes, startPage) }
            return
        }
        val l = PageLayout(pageSizes, width)
        layout = l
        viewport = Viewport(l, height).also { it.scrollToPage(startPage.coerceIn(0, pageSizes.size - 1)) }
        invalidate()
        onViewportChanged()
    }

    /** A rendering arrived from the worker. UI thread. */
    fun pageRendered(page: Int, widthPx: Int, bitmap: Bitmap) {
        inFlight.remove(page to widthPx)
        bitmaps.put(page, widthPx, bitmap)
        invalidate()
    }

    /** The page's ink changed: draw it again. */
    fun invalidatePage(page: Int) {
        bitmaps.invalidate(page)
        invalidate()
    }

    /**
     * How wide to render pages. Zoomed in, pages are rendered at 1.5x the screen and scaled up:
     * a full page at 4x would need about 285 MB.
     */
    private fun renderWidth(): Int = if ((viewport?.zoom ?: 1f) > 1.05f) (width * 1.5f).toInt() else width

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val v = viewport ?: return
        val w = renderWidth()
        for (page in v.visiblePages()) {
            val toScreen = v.pageToScreen(page)
            val topLeft = toScreen.map(Pt(0f, 0f))
            val bottomRight = toScreen.map(Pt(sizes[page].w, sizes[page].h))
            val dst = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)

            val exact = bitmaps.get(page, w)
            val shown = exact ?: bitmaps.anyFor(page)
            if (shown != null) canvas.drawBitmap(shown, null, dst, pagePaint) else canvas.drawRect(dst, placeholder)

            // Don't render mid-pinch: the width keeps changing. It's asked for once the pinch ends.
            if (exact == null && !pinching && inFlight.add(page to w)) requestRender(page, w)
        }
    }

    private val scroller = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            viewport?.scrollBy(dx, dy)
            invalidate()
            onViewportChanged()
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinching = true
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            viewport?.zoomAround(d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            onViewportChanged()
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            pinching = false
            invalidate() // now render at the new width
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Fingers only. The pen never scrolls (spec §6.2).
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!fingersDown) {
                fingersDown = true
                onFingerGestureStart()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (fingersDown) {
                fingersDown = false
                onFingerGestureEnd()
            }
        }
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) scroller.onTouchEvent(event)
        // A single tap does nothing, so a stray touch from the palm is harmless.
        return true
    }
}
