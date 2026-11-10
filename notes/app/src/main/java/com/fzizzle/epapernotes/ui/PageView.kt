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
 *
 * While fast-ink is on, the view isn't redrawn for each new stroke ([addStroke]): the panel
 * shows Onyx's ink, not this view. It catches up when the pen rests ([whenPenRests]), and
 * whoever pauses fast-ink redraws it first.
 */
class PageView(context: Context) : View(context) {

    /**
     * Fast-ink must pause while fingers move the page, so these tell the screen when. Only a
     * gesture that starts to pinch or to move a zoomed page counts: a touch that stays put, as
     * a palm's does, says nothing, so it doesn't pause fast-ink and redraw the page (owner,
     * 2026-11-10: palm touches made the screen refresh). [onFingerGestureEnd] follows every
     * [onFingerGestureStart], when the last finger lifts.
     */
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

    /** A finger held still on the page for about half a second, in view pixels. */
    var onFingerLongPress: (x: Float, y: Float) -> Unit = { _, _ -> }

    /** The selection box, snip rectangle and so on, drawn on top of the page. */
    var selectionLayer: SelectionLayer? = null

    /** Called after a fresh page rendering, made at the page's ink [version], is put on screen. */
    var onPageShown: (page: Int, version: Int) -> Unit = { _, _ -> }

    /** Asks for [page] to be drawn [widthPx] wide. The answer comes back through [pageRendered]. */
    var requestRender: (page: Int, widthPx: Int) -> Unit = { _, _ -> }

    /**
     * True while Onyx has the panel (fast-ink is on) and the pen is up. Only then may a page's
     * picture be swapped for a fresher one without anyone seeing ([bakeIfFull]): the panel isn't
     * showing this view, and no stroke is under way. The screen says; until it does, never.
     */
    var quietTime: () -> Boolean = { false }

    var viewport: Viewport? = null
        private set
    private var sizes: List<SizePt> = emptyList()
    private var toDisplay: List<Affine> = emptyList()

    /** A page rendering, and the page's ink version it contains. */
    private class Rendered(val bitmap: Bitmap, val version: Int)

    // A picture that leaves the cache has its memory freed at once (see PageBitmaps). That is
    // safe because the cache is the only thing that keeps page pictures, and it's only touched
    // on the UI thread: a rendering belongs to nobody else from the moment pageRendered gets
    // it, and onDraw takes a picture out and draws it in one go, with no put or invalidate in
    // between, so it never holds one that has been freed.
    private val bitmaps = PageBitmaps<Rendered>(200L * 1024 * 1024, { it.bitmap.byteCount.toLong() }) { it.bitmap.recycle() }

    // Renders on their way, and the ink version each will contain. A render that was on its
    // way when its page changed arrives stale (InkOverlay.isStale): it's kept for showing,
    // and asked for again.
    private val inFlight = mutableMapOf<Pair<Int, Int>, Int>()

    // Renders on their way when their page was left or rotated: thrown away when they come.
    // The worker answers in order, so each comes before any asked for again at that width.
    private val dropped = mutableListOf<Pair<Int, Int>>()

    // Renders on their way that nothing on screen is waiting for (see bakeIfFull): when one
    // comes, the view isn't redrawn and the screen isn't told.
    private val quietRenders = HashSet<Pair<Int, Int>>()

    private val ink = InkOverlay()

    private var pinching = false

    /** True from the first finger landing until the last one lifts ([onFingerGestureStart] to [onFingerGestureEnd]). */
    var fingersDown = false
        private set
    private var pastEdge = 0f
    private var movedThisGesture = false

    /** True once this finger gesture has begun to pinch or move the page ([onFingerGestureStart] said so). */
    private var gestureStarted = false

    /**
     * When the pen was last seen, hovering or touching. Hover events keep coming while it's in
     * range. A finger landing while the pen is near, or just after, is taken for the palm and
     * ignored (probe results §4: a palm leaks short touches just after the pen lifts).
     */
    private var penSeenAt = Long.MIN_VALUE / 2

    private val placeholder = Paint().apply { color = Color.rgb(235, 235, 235) }
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // Not smoothed: solid, like Onyx's live ink and the page's renderings of it (PdfFile.render).
    // This is what the panel shows of a new stroke once Onyx hands it back after a pause.
    private val inkPaint = Paint().apply { strokeCap = Paint.Cap.ROUND }
    private val inkPath = Path() // used again for each see-through stroke, rather than made anew

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
            quietRenders.remove(it)
            dropped += it
        }
    }

    /** The page's ink version now (see InkOverlay): a rendering asked for from now on contains everything up to it. */
    fun inkVersion(page: Int): Int = ink.version(page)

    /**
     * A stroke just written on [page]: show it until the page's rendering includes it. UI thread.
     *
     * [redraw] is false while Onyx draws the pen's ink on the panel itself (fast-ink is on).
     * The panel doesn't show this view then, so drawing the page again, with every stroke
     * written on it so far, after each new stroke was work nobody saw, and it got slower the
     * fuller the page was. The stroke is kept and drawn the next time the view draws:
     *  - whoever pauses fast-ink must call invalidate(), or the panel would show the page
     *    without the newest strokes (WritingActivity.pausePenAndRedraw and toolStateChanged do);
     *  - and the view catches up by itself once the pen has rested ([whenPenRests]).
     */
    fun addStroke(page: Int, stroke: Stroke, redraw: Boolean) {
        ink.add(page, stroke)
        if (redraw) invalidate()
        removeCallbacks(whenPenRests)
        postDelayed(whenPenRests, PEN_REST_MS)
    }

    /**
     * No new stroke for [PEN_REST_MS]. The view draws itself again, so its own picture has the
     * strokes it skipped ([addStroke]) even if nothing pauses fast-ink for a long while: should
     * the tablet ever show this view without the app pausing fast-ink first (a system panel
     * sliding over it, say), the strokes are there. With fast-ink on the panel ignores this
     * draw, as it ignored the one after every stroke before.
     */
    private val whenPenRests = Runnable {
        invalidate()
        bakeIfFull()
    }

    /**
     * The page on show has more than [InkOverlay.BAKE_AFTER] strokes drawn by hand on top of
     * its picture: ask for a fresh picture with them in it, so later redraws (a pinch, a
     * dragged selection) don't draw them one by one. Unlike [pageChanged], nothing is waiting
     * to see this picture, so it's a quiet render: see [pageRendered] for why its arrival
     * neither redraws the view nor pauses fast-ink.
     */
    private fun bakeIfFull() {
        val v = viewport ?: return
        if (pinching || fingersDown || !quietTime()) return
        val page = v.page
        val w = renderWidth(v, page)
        // No picture at this width, a stale one, or one on its way: the usual path has it in hand.
        val have = bitmaps.get(page, w) ?: return
        if (ink.isStale(page, have.version) || inFlight.containsKey(page to w)) return
        val byHand = ink.count(page, have.version)
        if (byHand <= InkOverlay.BAKE_AFTER) return
        // As in ensureRendered: every stroke counted now reached the PDF before this request.
        inFlight[page to w] = ink.version(page)
        quietRenders += page to w
        android.util.Log.i("Notes", "render requested page=$page width=$w version=${ink.version(page)} (quiet: $byHand strokes drawn by hand)")
        requestRender(page, w)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(whenPenRests)
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
        if (dropped.remove(page to widthPx)) return
        inFlight.remove(page to widthPx)
        quietRenders.remove(page to widthPx)
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
        if (dropped.remove(page to widthPx)) {
            // Asked for before the page was left or rotated: never shown, so its memory goes back at once.
            bitmap.recycle()
            return
        }
        val version = inFlight.remove(page to widthPx) ?: 0
        val quiet = quietRenders.remove(page to widthPx)
        val stale = ink.isStale(page, version)
        android.util.Log.i("Notes", "rendered page=$page width=$widthPx version=$version of ${ink.version(page)}${if (stale) " (stale, asking again)" else ""}${if (quiet) " (quiet)" else ""}")
        if (quiet && !stale && page == viewport?.page) {
            val have = bitmaps.get(page, widthPx)
            if (have != null && !ink.isStale(page, have.version)) {
                // A quiet render (bakeIfFull), and the picture it was to replace is still good,
                // so nothing on screen waits for this one. It shows the same page as that
                // picture plus the strokes drawn by hand, so it's swapped in without a redraw
                // and without telling the screen: no pause of fast-ink, nothing new on the
                // panel. The next draw, whenever something else asks for one, uses it.
                // Only while Onyx still has the panel, though. If fast-ink has gone off since
                // (a selection, a dialog), the view is what the panel shows, and its strokes
                // must not change their look under the owner's eyes: thrown away, asked for
                // again at a later rest.
                if (quietTime()) bitmaps.put(page, widthPx, Rendered(bitmap, version)) else bitmap.recycle()
                return
            }
            // The picture it was to replace has gone or is stale: this one is needed after all.
        }
        // A stale one for a page that was left may lack strokes InkOverlay.left dropped: not kept.
        if (stale && page != viewport?.page) {
            bitmap.recycle()
            return
        }
        // Otherwise even a stale one is kept: it's closer to the page than what's showing. Drawing asks again.
        bitmaps.put(page, widthPx, Rendered(bitmap, version))
        // Only the page on show: a neighbour got ready for the next page turn changes nothing in view.
        if (page == viewport?.page) invalidate()
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
        val started = System.nanoTime()
        val v = viewport ?: return canvas.drawColor(Color.WHITE)
        // Beside the page is shaded, so it's clear where the page, and writing, ends (owner,
        // 2026-11-10: the white band at the sides looked like page the pen wouldn't write on).
        canvas.drawColor(BESIDE_PAGE)
        val page = v.page
        val toScreen = v.pageToScreen()
        val topLeft = toScreen.map(Pt(0f, 0f))
        val bottomRight = toScreen.map(Pt(sizes[page].w, sizes[page].h))
        val dst = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)

        val w = renderWidth(v, page)
        // A stale rendering is still drawn: an old page for a moment is calmer than a blank one.
        // Taken from the cache and drawn straight away, and nothing below puts a picture into
        // the cache or drops one (a render asked for here comes back later, in a message of
        // its own), so this one can't have been freed. Checked all the same: drawing a freed
        // picture would crash the app, and a grey page until the fresh picture comes wouldn't.
        var shown = bitmaps.get(page, w) ?: bitmaps.anyFor(page)
        if (shown != null && shown.bitmap.isRecycled) {
            android.util.Log.w("Notes", "page=$page: its picture was freed while still kept; rendering it again")
            bitmaps.invalidate(page) // so ensureRendered below asks for a fresh one
            shown = null
        }
        if (shown != null) canvas.drawBitmap(shown.bitmap, null, dst, pagePaint) else canvas.drawRect(dst, placeholder)

        // Whatever this rendering is missing, whenever it was made.
        val missing = ink.newerThan(page, shown?.version ?: 0)
        val pdfToScreen = toDisplay[page].then(toScreen)
        val pxPerPt = v.pxPerPt()
        missing.forEach { drawStroke(canvas, it, pdfToScreen, pxPerPt) }

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

        // Only the slow ones, so the log shows the draws that could make the pen or a drag
        // stutter. "overlay" is how many strokes were drawn here on top of the page's picture.
        val ms = (System.nanoTime() - started) / 1_000_000
        if (ms > SLOW_DRAW_MS) android.util.Log.i("Notes", "draw ms=$ms overlay=${missing.size}")
    }

    /**
     * A close version of the saved ink, standing in until the page re-renders. Each point is
     * mapped to the screen as it's drawn (the same sum as Affine.map), with no list of mapped
     * points made first: on a full page this runs for thousands of points per redraw.
     */
    private fun drawStroke(canvas: Canvas, stroke: Stroke, pdfToScreen: Affine, pxPerPt: Float) {
        val points = stroke.points
        if (points.isEmpty()) return
        val t = pdfToScreen
        val style = StrokeStyle.of(stroke.kind)
        inkPaint.color = Color.rgb((style.rgb[0] * 255).toInt(), (style.rgb[1] * 255).toInt(), (style.rgb[2] * 255).toInt())
        inkPaint.alpha = (style.opacity * 255).toInt()
        if (style.opacity < 1f) {
            // See-through ink is drawn as one path, once: overlapping pieces would show darker.
            val path = inkPath
            path.reset()
            for (i in points.indices) {
                val p = points[i]
                val x = t.a * p.x + t.c * p.y + t.e
                val y = t.b * p.x + t.d * p.y + t.f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (points.size == 1) path.lineTo(x + 0.01f, y)
            }
            inkPaint.strokeWidth = style.maxWidth * pxPerPt
            inkPaint.style = Paint.Style.STROKE
            inkPaint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, inkPaint)
            return
        }
        var fromX = 0f
        var fromY = 0f
        for (i in points.indices) {
            val p = points[i]
            val x = t.a * p.x + t.c * p.y + t.e
            val y = t.b * p.x + t.d * p.y + t.f
            // The first point is drawn as a dot: a line from itself to itself.
            if (i == 0) {
                fromX = x
                fromY = y
            }
            inkPaint.strokeWidth = style.widthAt(p.pressure, stroke.widthScale) * pxPerPt
            canvas.drawLine(fromX, fromY, x, y, inkPaint)
            fromX = x
            fromY = y
        }
    }

    private val scroller = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onFingerTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) = onFingerLongPress(e.x, e.y)

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val v = viewport ?: return true
            // At normal size the whole page is showing: a swipe does nothing (spec §6.2).
            if (v.zoom <= 1.05f) return true
            startGesture()
            movedThisGesture = true
            val beyond = v.panBy(dx, dy)
            pastEdge = if (beyond != 0f && (pastEdge == 0f || (beyond > 0) == (pastEdge > 0))) pastEdge + beyond else 0f
            invalidate()
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            startGesture()
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
        if (isPen(event)) penSeenAt = event.eventTime
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            android.util.Log.i("Notes", "pen ${MotionEvent.actionToString(event.actionMasked)} tool=${event.getToolType(0)} button=${penButtonOf(event)}")
        }
        // Not on exit: the pen leaves hover just as it lands, and a missing button there must
        // not turn a grey lasso line back into ink.
        if (isPen(event) && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT) onStylusHover(penButtonOf(event))
        return super.onHoverEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val m = event.actionMasked
        if (isPen(event)) penSeenAt = event.eventTime
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
            val penNear = event.eventTime - penSeenAt < PEN_NEAR_MS
            ignoringFingers = penNear || !fingersAllowed()
            if (ignoringFingers) android.util.Log.i("Notes", "fingers ignored: ${if (penNear) "the pen is near" else "the pen is writing"}")
        }
        // Taken (true), so the rest of the gesture comes here too and is dropped the same way.
        if (ignoringFingers) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pastEdge = 0f
                movedThisGesture = false
                gestureStarted = false
                fingersDown = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Pushed well past the top or bottom of a zoomed page: turn the page.
                if (abs(pastEdge) > height * TURN_PUSH_FRACTION && turn(forward = pastEdge > 0)) movedThisGesture = true
                pastEdge = 0f
                fingersDown = false
                if (gestureStarted) {
                    gestureStarted = false
                    onFingerGestureEnd(movedThisGesture)
                }
            }
        }
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) scroller.onTouchEvent(event)
        return true
    }

    /** The gesture begins to pinch or move the page: fast-ink pauses now, before the page moves. */
    private fun startGesture() {
        if (gestureStarted) return
        gestureStarted = true
        onFingerGestureStart()
    }

    private fun isPen(event: MotionEvent): Boolean = when (event.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        else -> false
    }

    companion object {
        /** Beside the page: a light grey, darker than [placeholder]'s page-not-drawn-yet grey. */
        private val BESIDE_PAGE = Color.rgb(215, 215, 215)

        /** A draw is only logged when it takes longer than this: half of a 60-a-second frame. */
        private const val SLOW_DRAW_MS = 8L

        /** How long after the last new stroke the pen counts as resting (see [whenPenRests]). */
        private const val PEN_REST_MS = 500L

        /**
         * Button 1 is an ordinary button flag. Button 2 is too while it's pressed with the pen
         * already in range; held as the pen comes into range, it makes the pen report as an
         * eraser instead (probe results §1, a two-button pen with no eraser end). Either way
         * button 2 means the eraser, and it wins over button 1.
         */
        fun penButtonOf(event: MotionEvent): PenButton = when {
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER -> PenButton.ERASER_END
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY) -> PenButton.ERASER_END
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) -> PenButton.LASSO_BUTTON
            else -> PenButton.NONE
        }

        /** A finger landing this soon after the pen was last seen is taken for the palm. */
        const val PEN_NEAR_MS = 500L

        /** How far past the edge a zoomed page must be pushed to turn, as a share of the screen height. */
        const val TURN_PUSH_FRACTION = 0.15f
    }
}
