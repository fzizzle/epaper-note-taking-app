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
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeStyle
import kotlin.math.abs

/**
 * Shows one page at a time (spec §6.2) and moves it with fingers: two fingers pinch-zoom;
 * zoomed in, one finger moves around the page, and pushing on past the top or bottom edge
 * turns the page. The pen is ignored here: while fast-ink is on, Onyx handles the pen.
 *
 * New strokes are drawn here too, on top of the page, until the page has been rendered again
 * from the PDF with them in it. That keeps them on screen whenever the view redraws, without
 * having to re-render while the owner is writing.
 */
class PageView(context: Context) : View(context) {

    /** Fast-ink must pause while fingers move the page, so these tell the screen when. */
    var onFingerGestureStart: () -> Unit = {}
    var onFingerGestureEnd: () -> Unit = {}
    var onViewportChanged: () -> Unit = {}

    /** Called after a page rendering is put on screen. */
    var onPageShown: (page: Int) -> Unit = {}

    /** Asks for [page] to be drawn [widthPx] wide. The answer comes back through [pageRendered]. */
    var requestRender: (page: Int, widthPx: Int) -> Unit = { _, _ -> }

    var viewport: Viewport? = null
        private set
    private var sizes: List<SizePt> = emptyList()
    private var toDisplay: List<Affine> = emptyList()

    private val bitmaps = PageBitmaps<Bitmap>(200L * 1024 * 1024) { it.byteCount.toLong() }

    // Renders on their way, and how many of the page's new strokes each will already include.
    private val inFlight = mutableMapOf<Pair<Int, Int>, Int>()

    // Strokes written since the page was last rendered, per page, oldest first.
    private val unrendered = mutableMapOf<Int, MutableList<Stroke>>()

    private var pinching = false
    private var fingersDown = false
    private var pastEdge = 0f

    private val placeholder = Paint().apply { color = Color.rgb(235, 235, 235) }
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    /** Lays out the document. [pdfToDisplay] is each page's PDF-to-display transform. UI thread. */
    fun setPages(pageSizes: List<SizePt>, pdfToDisplay: List<Affine>, startPage: Int) {
        if (width == 0) {
            post { setPages(pageSizes, pdfToDisplay, startPage) }
            return
        }
        sizes = pageSizes
        toDisplay = pdfToDisplay
        viewport = Viewport(pageSizes, width, height).also { it.goTo(startPage) }
        invalidate()
        onViewportChanged()
    }

    /** Previous or next page. Returns false at either end of the document. */
    fun turn(forward: Boolean): Boolean {
        val v = viewport ?: return false
        val leaving = v.page
        val moved = if (forward) v.next() else v.previous()
        if (!moved) return false
        // Next time this page is visited, render it afresh so its new strokes come from the PDF.
        if (unrendered[leaving].orEmpty().isNotEmpty()) bitmaps.invalidate(leaving)
        invalidate()
        onViewportChanged()
        return true
    }

    /** A stroke just written on [page]: show it until the page's rendering includes it. UI thread. */
    fun addStroke(page: Int, stroke: Stroke) {
        unrendered.getOrPut(page) { mutableListOf() }.add(stroke)
        invalidate()
    }

    /** A rendering arrived from the worker. UI thread. */
    fun pageRendered(page: Int, widthPx: Int, bitmap: Bitmap) {
        val included = inFlight.remove(page to widthPx) ?: 0
        unrendered[page]?.let { list -> repeat(minOf(included, list.size)) { list.removeAt(0) } }
        android.util.Log.i("Notes", "rendered page=$page width=$widthPx included=$included stillUnrendered=${unrendered[page]?.size ?: 0}")
        bitmaps.put(page, widthPx, bitmap)
        invalidate()
        onPageShown(page)
    }

    /**
     * How wide to render the page. Zoomed in, it's rendered at 1.5x its fitted size and
     * scaled up: a full page at 4x would need about 285 MB.
     */
    private fun renderWidth(v: Viewport, page: Int): Int {
        val fitted = sizes[page].w * v.pxPerPt() / v.zoom
        return (if (v.zoom > 1.05f) fitted * 1.5f else fitted).toInt()
    }

    private fun ensureRendered(page: Int, widthPx: Int) {
        if (bitmaps.get(page, widthPx) != null || inFlight.containsKey(page to widthPx)) return
        // Strokes counted now were all sent to the PDF before this request, and the worker
        // handles things in order, so this rendering will include them.
        inFlight[page to widthPx] = unrendered[page]?.size ?: 0
        android.util.Log.i("Notes", "render requested page=$page width=$widthPx includes=${inFlight[page to widthPx]}")
        requestRender(page, widthPx)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val v = viewport ?: return
        val page = v.page
        val toScreen = v.pageToScreen()
        val topLeft = toScreen.map(Pt(0f, 0f))
        val bottomRight = toScreen.map(Pt(sizes[page].w, sizes[page].h))
        val dst = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)

        val w = renderWidth(v, page)
        val shown = bitmaps.get(page, w) ?: bitmaps.anyFor(page)
        if (shown != null) canvas.drawBitmap(shown, null, dst, pagePaint) else canvas.drawRect(dst, placeholder)

        unrendered[page]?.forEach { drawStroke(canvas, it, toDisplay[page].then(toScreen), v.pxPerPt()) }

        // Don't render mid-pinch: the width keeps changing. It's asked for once the pinch ends.
        if (!pinching) {
            ensureRendered(page, w)
            // Get the neighbours ready, so turning the page is instant.
            if (v.zoom <= 1.05f) {
                if (page + 1 < v.pageCount) ensureRendered(page + 1, renderWidth(v, page + 1))
                if (page > 0) ensureRendered(page - 1, renderWidth(v, page - 1))
            }
        }
    }

    /** A rough version of the saved ink, good enough to stand in until the page re-renders. */
    private fun drawStroke(canvas: Canvas, stroke: Stroke, pdfToScreen: Affine, pxPerPt: Float) {
        val style = StrokeStyle.of(stroke.kind)
        inkPaint.color = Color.rgb((style.rgb[0] * 255).toInt(), (style.rgb[1] * 255).toInt(), (style.rgb[2] * 255).toInt())
        inkPaint.alpha = (style.opacity * 255).toInt()
        val pts = stroke.points.map { pdfToScreen.map(Pt(it.x, it.y)) }
        for (i in pts.indices) {
            val a = pts[if (i == 0) 0 else i - 1]
            val b = pts[i]
            inkPaint.strokeWidth = style.widthAt(stroke.points[i].pressure) * pxPerPt
            canvas.drawLine(a.x, a.y, b.x, b.y, inkPaint)
        }
    }

    private val scroller = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val v = viewport ?: return true
            // At normal size the whole page is showing: a swipe does nothing (spec §6.2).
            if (v.zoom <= 1.05f) return true
            val beyond = v.panBy(dx, dy)
            pastEdge = if (beyond != 0f && (pastEdge == 0f || (beyond > 0) == (pastEdge > 0))) pastEdge + beyond else 0f
            invalidate()
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
        // Fingers only. The pen never moves the page (spec §6.2).
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pastEdge = 0f
                if (!fingersDown) {
                    fingersDown = true
                    onFingerGestureStart()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Pushed well past the top or bottom of a zoomed page: turn the page.
                if (abs(pastEdge) > height * TURN_PUSH_FRACTION) turn(forward = pastEdge > 0)
                pastEdge = 0f
                if (fingersDown) {
                    fingersDown = false
                    onFingerGestureEnd()
                }
            }
        }
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) scroller.onTouchEvent(event)
        return true
    }

    companion object {
        /** How far past the edge a zoomed page must be pushed to turn, as a share of the screen height. */
        const val TURN_PUSH_FRACTION = 0.15f
    }
}
