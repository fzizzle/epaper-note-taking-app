package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeStyle
import com.fzizzle.epapernotes.tools.PenButton
import kotlin.math.abs

/**
 * Shows one page at a time (spec §6.2) and moves it with fingers: two fingers pinch-zoom;
 * zoomed in, one finger moves around the page, and pushing on past the top or bottom edge
 * turns the page. While fast-ink is on, Onyx handles the pen and it's ignored here; while it's
 * paused for a selection, a snip or a paste, the pen goes to [penHandler].
 *
 * New strokes are drawn here too, on top of any page rendering that doesn't contain them yet
 * (see InkOverlay). That keeps them on screen whenever the view redraws, at any zoom, without
 * re-rendering while the owner is writing.
 */
class PageView(context: Context) : View(context) {

    /** Fast-ink must pause while fingers move the page, so these tell the screen when. */
    var onFingerGestureStart: () -> Unit = {}
    /** [moved] is true when the gesture zoomed or moved the page. */
    var onFingerGestureEnd: (moved: Boolean) -> Unit = {}
    var onViewportChanged: () -> Unit = {}

    /**
     * The pen touched down, or started or went on hovering, with this button held. Hover
     * comes first, often 100–500 ms before touch-down (probe results §2), so the live line can
     * be set before the first point. Called before the finger handling, for pen events only.
     */
    var onStylusDown: (PenButton) -> Unit = {}
    var onStylusHover: (PenButton) -> Unit = {}

    /**
     * While set, pen events go here instead of being ignored. Set only while fast-ink is off
     * for a tool (ToolState.fastInkOn is false): with fast-ink on, Onyx has the pen.
     */
    var penHandler: ((MotionEvent) -> Boolean)? = null

    /**
     * Asked when a finger lands. False means fingers are ignored for now: that whole gesture,
     * until the last finger lifts, does nothing at all. The screen says false while the pen is
     * writing, so a palm that lands mid-stroke neither pauses fast-ink nor moves the page (spec §6.2).
     */
    var fingersAllowed: () -> Boolean = { true }

    /** True from a finger landing while [fingersAllowed] said no, until the next finger gesture begins. */
    private var ignoringFingers = false

    /** A one-finger tap, in view pixels. */
    var onFingerTap: (x: Float, y: Float) -> Unit = { _, _ -> }

    /** The selection box, snip rectangle and so on, drawn on top of the page. */
    var selectionLayer: SelectionLayer? = null

    /** Called after a fresh page rendering, made at the page's ink [version], is put on screen. */
    var onPageShown: (page: Int, version: Int) -> Unit = { _, _ -> }

    /** Asks for [page] to be drawn [widthPx] wide. The answer comes back through [pageRendered]. */
    var requestRender: (page: Int, widthPx: Int) -> Unit = { _, _ -> }

    var viewport: Viewport? = null
        private set
    private var sizes: List<SizePt> = emptyList()
    private var toDisplay: List<Affine> = emptyList()

    /** A page rendering, and the page's ink version it contains. */
    private class Rendered(val bitmap: Bitmap, val version: Int)

    private val bitmaps = PageBitmaps<Rendered>(200L * 1024 * 1024) { it.bitmap.byteCount.toLong() }

    // Renders on their way, and the ink version each will contain. A render that was on its
    // way when its page changed arrives stale (InkOverlay.isStale): it's kept for showing,
    // and asked for again.
    private val inFlight = mutableMapOf<Pair<Int, Int>, Int>()

    // Renders on their way when their page was left or rotated: thrown away when they come.
    // The worker answers in order, so each comes before any asked for again at that width.
    private val dropped = mutableListOf<Pair<Int, Int>>()

    private val ink = InkOverlay()

    private var pinching = false
    private var fingersDown = false
    private var pastEdge = 0f
    private var movedThisGesture = false

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
        forget(leaving)
        invalidate()
        onViewportChanged()
        return true
    }

    /** Jumps straight to [page] (kept within the document). */
    fun goTo(page: Int) {
        val v = viewport ?: return
        val leaving = v.page
        if (page == leaving) return
        v.goTo(page)
        forget(leaving)
        invalidate()
        onViewportChanged()
    }

    /** Next time [page] is visited, render it afresh so its new ink comes from the PDF. */
    private fun forget(page: Int) {
        if (ink.version(page) == 0) return
        dropRenderings(page)
        // A rendering still on its way may lack strokes this drops, so dropRenderings came first.
        ink.left(page)
    }

    /** [page] turned: its renderings are the wrong way round. Call before [setPages] lays it out anew. UI thread. */
    fun pageRotated(page: Int) {
        dropRenderings(page)
    }

    private fun dropRenderings(page: Int) {
        bitmaps.invalidate(page)
        inFlight.keys.filter { it.first == page }.forEach {
            inFlight.remove(it)
            dropped += it
        }
    }

    /** The page's ink version now (see InkOverlay): a rendering asked for from now on contains everything up to it. */
    fun inkVersion(page: Int): Int = ink.version(page)

    /** A stroke just written on [page]: show it until the page's rendering includes it. UI thread. */
    fun addStroke(page: Int, stroke: Stroke) {
        ink.add(page, stroke)
        invalidate()
    }

    /**
     * [page]'s ink changed in a way the overlay can't show, like an erase or a move, and the
     * change has reached the PDF: render it afresh. The old rendering stays on screen until
     * the fresh one arrives, so nothing is redrawn now. UI thread.
     */
    fun pageChanged(page: Int) {
        ink.changed(page)
        val v = viewport ?: return
        if (page == v.page && !pinching) ensureRendered(page, renderWidth(v, page))
    }

    /** Rendering failed (a damaged page, say). Forget it, so it's asked for again next time. UI thread. */
    fun renderFailed(page: Int, widthPx: Int) {
        if (!dropped.remove(page to widthPx)) inFlight.remove(page to widthPx)
    }

    /** Where the page on show is, for the selection layer. Null until the pages are laid out. */
    fun frame(): PageFrame? {
        val v = viewport ?: return null
        return PageFrame(toDisplay[v.page], v.pageToScreen(), sizes[v.page], width, height)
    }

    /** The view changed size, for instance the tablet turned: refit the same page. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val old = viewport ?: return
        if (w == 0 || h == 0) return
        viewport = Viewport(sizes, w, h).also { it.goTo(old.page) }
        invalidate()
        onViewportChanged()
    }

    /** A rendering arrived from the worker. UI thread. */
    fun pageRendered(page: Int, widthPx: Int, bitmap: Bitmap) {
        if (dropped.remove(page to widthPx)) return // asked for before the page was left or rotated
        val version = inFlight.remove(page to widthPx) ?: 0
        val stale = ink.isStale(page, version)
        android.util.Log.i("Notes", "rendered page=$page width=$widthPx version=$version of ${ink.version(page)}${if (stale) " (stale, asking again)" else ""}")
        // A stale one for a page that was left may lack strokes InkOverlay.left dropped: not kept.
        if (stale && page != viewport?.page) return
        // Otherwise even a stale one is kept: it's closer to the page than what's showing. Drawing asks again.
        bitmaps.put(page, widthPx, Rendered(bitmap, version))
        invalidate()
        if (!stale) onPageShown(page, version)
    }

    /**
     * How wide to render the page. Zoomed in, it's rendered at 1.5x its fitted size and
     * scaled up: a full page at 4x would need about 285 MB.
     */
    private fun renderWidth(v: Viewport, page: Int): Int {
        // Rounded from the fitted width, so floating-point noise never asks for 2789 then 2790.
        val fitted = Math.round(sizes[page].w * v.pxPerPt() / v.zoom)
        return if (v.zoom > 1.05f) Math.round(fitted * 1.5f) else fitted
    }

    private fun ensureRendered(page: Int, widthPx: Int) {
        val have = bitmaps.get(page, widthPx)
        if ((have != null && !ink.isStale(page, have.version)) || inFlight.containsKey(page to widthPx)) return
        // Strokes counted now were all sent to the PDF before this request, and the worker
        // handles things in order, so this rendering will contain them.
        inFlight[page to widthPx] = ink.version(page)
        android.util.Log.i("Notes", "render requested page=$page width=$widthPx version=${ink.version(page)}")
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
        // A stale rendering is still drawn: an old page for a moment is calmer than a blank one.
        val shown = bitmaps.get(page, w) ?: bitmaps.anyFor(page)
        if (shown != null) canvas.drawBitmap(shown.bitmap, null, dst, pagePaint) else canvas.drawRect(dst, placeholder)

        // Whatever this rendering is missing, whenever it was made.
        val missing = ink.newerThan(page, shown?.version ?: 0)
        missing.forEach { drawStroke(canvas, it, toDisplay[page].then(toScreen), v.pxPerPt()) }

        selectionLayer?.draw(canvas, PageFrame(toDisplay[page], toScreen, sizes[page], width, height))

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

    /** A close version of the saved ink, standing in until the page re-renders. */
    private fun drawStroke(canvas: Canvas, stroke: Stroke, pdfToScreen: Affine, pxPerPt: Float) {
        val style = StrokeStyle.of(stroke.kind)
        inkPaint.color = Color.rgb((style.rgb[0] * 255).toInt(), (style.rgb[1] * 255).toInt(), (style.rgb[2] * 255).toInt())
        inkPaint.alpha = (style.opacity * 255).toInt()
        val pts = stroke.points.map { pdfToScreen.map(Pt(it.x, it.y)) }
        if (style.opacity < 1f) {
            // See-through ink is drawn as one path, once: overlapping pieces would show darker.
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { path.lineTo(it.x, it.y) }
            if (pts.size == 1) path.lineTo(pts[0].x + 0.01f, pts[0].y)
            inkPaint.strokeWidth = style.maxWidth * pxPerPt
            inkPaint.style = Paint.Style.STROKE
            inkPaint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, inkPaint)
            return
        }
        for (i in pts.indices) {
            val a = pts[if (i == 0) 0 else i - 1]
            val b = pts[i]
            inkPaint.strokeWidth = style.widthAt(stroke.points[i].pressure, stroke.widthScale) * pxPerPt
            canvas.drawLine(a.x, a.y, b.x, b.y, inkPaint)
        }
    }

    private val scroller = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onFingerTap(e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val v = viewport ?: return true
            // At normal size the whole page is showing: a swipe does nothing (spec §6.2).
            if (v.zoom <= 1.05f) return true
            movedThisGesture = true
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
            movedThisGesture = true
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

    // Diagnostics for the owner's "have to wait 2 seconds to zoom after a stroke": when does the
    // pen leave the screen's range, and how late do finger touches arrive?
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            android.util.Log.i("Notes", "pen ${MotionEvent.actionToString(event.actionMasked)} tool=${event.getToolType(0)}")
        }
        // Not on exit: the pen leaves hover just as it lands, and a missing button there must
        // not turn a grey lasso line back into ink.
        if (isPen(event) && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT) onStylusHover(penButtonOf(event))
        return super.onHoverEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val m = event.actionMasked
        if (m == MotionEvent.ACTION_DOWN && isPen(event)) onStylusDown(penButtonOf(event))
        if (m == MotionEvent.ACTION_DOWN || m == MotionEvent.ACTION_POINTER_DOWN) {
            val lag = android.os.SystemClock.uptimeMillis() - event.eventTime
            android.util.Log.i("Notes", "touch ${MotionEvent.actionToString(m)} tool=${event.getToolType(event.actionIndex)} pointers=${event.pointerCount} deliveredLateMs=$lag")
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The pen never moves the page (spec §6.2). While fast-ink is off for a tool, it works the tool.
        if (isPen(event)) return penHandler?.invoke(event) ?: false
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoringFingers = !fingersAllowed()
            if (ignoringFingers) android.util.Log.i("Notes", "fingers ignored: the pen is writing")
        }
        // Taken (true), so the rest of the gesture comes here too and is dropped the same way.
        if (ignoringFingers) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pastEdge = 0f
                movedThisGesture = false
                if (!fingersDown) {
                    fingersDown = true
                    onFingerGestureStart()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Pushed well past the top or bottom of a zoomed page: turn the page.
                if (abs(pastEdge) > height * TURN_PUSH_FRACTION && turn(forward = pastEdge > 0)) movedThisGesture = true
                pastEdge = 0f
                if (fingersDown) {
                    fingersDown = false
                    onFingerGestureEnd(movedThisGesture)
                }
            }
        }
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) scroller.onTouchEvent(event)
        return true
    }

    private fun isPen(event: MotionEvent): Boolean = when (event.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        else -> false
    }

    companion object {
        /**
         * Button 2 makes the pen report as an eraser; button 1 is an ordinary button flag
         * (probe results §1). Button 1's flag is ignored on the eraser, which wins.
         */
        fun penButtonOf(event: MotionEvent): PenButton = when {
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER -> PenButton.ERASER_END
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) -> PenButton.LASSO_BUTTON
            else -> PenButton.NONE
        }

        /** How far past the edge a zoomed page must be pushed to turn, as a share of the screen height. */
        const val TURN_PUSH_FRACTION = 0.15f
    }
}
