package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.doc.Change
import com.fzizzle.epapernotes.doc.DocumentSession
import com.fzizzle.epapernotes.doc.RemoveItems
import com.fzizzle.epapernotes.doc.ReplaceItems
import com.fzizzle.epapernotes.doc.SessionEvent
import com.fzizzle.epapernotes.doc.WorkerScheduler
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.transformed
import com.fzizzle.epapernotes.pdf.PdfFile
import com.fzizzle.epapernotes.tools.Clipboard
import com.fzizzle.epapernotes.tools.PenButton
import com.fzizzle.epapernotes.tools.Tool
import com.fzizzle.epapernotes.tools.ToolState
import com.fzizzle.epapernotes.tools.liveLine
import com.fzizzle.epapernotes.tools.placedOn
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The writing screen (spec §6.2): one page at a time fills the screen under one thin strip.
 * Plan 2 has the back button, the pen and highlighter, and ‹ page indicator ›. The other
 * tools come in plan 3.
 */
class WritingActivity : Activity() {

    private lateinit var session: DocumentSession
    private lateinit var pages: PageView
    private lateinit var indicator: TextView
    private lateinit var lastPages: LastPageStore
    private lateinit var path: String
    private var pageCount = 0
    private lateinit var pen: PenInput
    private lateinit var penButton: StripButton
    private lateinit var highlighterButton: StripButton
    private lateinit var previousButton: StripButton
    private lateinit var nextButton: StripButton
    private val toolState = ToolState()
    private lateinit var layer: SelectionLayer

    /** The button the pen last showed while hovering or landing. UI thread. */
    private var hoverButton = PenButton.NONE

    /** For each page, PDF space to display points, inverted: how a pen point finds its place in the PDF. */
    private var displayToPdf: List<Affine> = emptyList()
    private var pdfToDisplay: List<Affine> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    /** True once the pages are laid out. The pen stays off until then: a stroke would have nowhere to go. */
    private var documentShown = false

    /**
     * Screen pixels to PDF space for the page on show, published by the UI thread whenever the
     * view changes, and copied by the pen thread when the pen touches down. Each stroke is
     * placed with the view as it was when it started, even if the owner zooms or turns the
     * page before Onyx hands the stroke over.
     */
    @Volatile private var penMapping: Pair<Int, Affine>? = null
    @Volatile private var strokeMapping: Pair<Int, Affine>? = null

    /**
     * The same for the tool: what a stroke would be if the pen landed now, worked out by the
     * UI thread from [toolState], and the one the current stroke got. A button counts only if
     * it's held at touch-down (spec §6.3). Onyx's pen-down and Android's touch-down race by a
     * few ms (probe results §4), so both latch it; the touch-down carries the button for sure.
     */
    @Volatile private var penChoice = PenButton.NONE to Tool.PEN
    @Volatile private var strokeChoice: Pair<PenButton, Tool>? = null

    /**
     * Pages with a change on its way that the page view can't show by itself, like an erase.
     * [SessionEvent.PageChanged] also follows every pen stroke, and redrawing then would pause
     * fast-ink after each one (plan 2, problem 1), so only these pages are redrawn. UI thread.
     */
    private val redrawWhenChanged = HashSet<Int>()

    /**
     * A show was skipped because the pen was down: say, an erased page arrived while the owner
     * was already writing again. It runs once the pen has rested for [SHOW_RETRY_IDLE_MS], so
     * erased strokes and grey trails don't stay on the panel. The next pen-down cancels the
     * wait, and the next lift starts it again.
     */
    @Volatile private var showPending = false
    private val retryShow = Runnable {
        if (!showPending || pen.isWriting) return@Runnable
        Log.i(TAG, "show retried after idle")
        showWithoutFastInk()
    }

    /**
     * The selection, as the PDF has it now: always the items of the change that was journaled
     * (DocumentSession.record), whose ids can differ from the ones asked for. UI thread.
     */
    private var selected: List<Item> = emptyList()
    private var selectedPage = -1

    /** The page last on show: turning it lets go of the selection and an armed paste. */
    private var shownPage = -1
    private var dialogOpen = false

    /**
     * True while the screen, not Onyx, follows a pen stroke (fast-ink off for a tool). Fast-ink
     * must not come back then, or Onyx would pick the stroke up halfway. UI thread.
     */
    private var screenPenDown = false

    /** True while [PageView.penHandler] is set. Onyx's thread reads it at pen-down. */
    @Volatile private var screenHasPen = false

    /** Whether the stroke Onyx is following started while Onyx had the pen. */
    @Volatile private var onyxOwnsStroke = true

    /** Fast-ink had to go off for a tool while the pen was down; the lift does it. */
    @Volatile private var pauseWhenLifted = false

    /**
     * Turns fast-ink back on if it's wanted now ([fastInkWanted]). Every resume goes through
     * here, so a show, a finger gesture or a dialog closing can't turn it on under a selection
     * or a dialog. Not while the screen has the pen down; its lift tries again.
     */
    private val resumeFastInk = Runnable {
        if (screenPenDown) return@Runnable
        if (toolState.fastInkOn()) {
            pages.penHandler = null
            screenHasPen = false
        }
        // Onyx writing means fast-ink is on already.
        if (fastInkWanted() && !pen.isWriting) {
            Log.i(TAG, "resume fast-ink")
            pen.resume()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        lastPages = LastPageStore(this)

        pages = PageView(this)
        indicator = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum" // even-width digits, so the number doesn't jiggle as pages turn
            setOnClickListener { askForPage() } // tap the number to jump to a page
        }
        val stripHeight = (resources.displayMetrics.heightPixels * 0.05f).toInt() // about 5% (spec §6.2)
        layer = SelectionLayer(handlePx = stripHeight / 3f, listener = selectionListener) { pages.invalidate() }
        pages.selectionLayer = layer
        pages.onFingerTap = { x, y -> pages.frame()?.let { layer.onFingerTap(x, y, it, pages.width) } }
        val gap = stripHeight / 8
        fun square() = LinearLayout.LayoutParams(stripHeight - 2 * gap, stripHeight - 2 * gap).apply { marginEnd = gap }

        penButton = StripButton(this, StripButton.Glyph.PEN, "Pen").apply { setOnClickListener { choose(Tool.PEN) } }
        highlighterButton = StripButton(this, StripButton.Glyph.HIGHLIGHTER, "Highlighter").apply { setOnClickListener { choose(Tool.HIGHLIGHTER) } }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(gap, 0, gap, 0)
            setBackgroundColor(Color.WHITE)
            addView(StripButton(this@WritingActivity, StripButton.Glyph.BACK, "Back to the list").apply { setOnClickListener { finish() } }, square().apply { marginEnd = gap * 3 })
            addView(penButton, square())
            addView(highlighterButton, square())
            addView(View(this@WritingActivity), LinearLayout.LayoutParams(0, 1, 1f)) // pushes the page controls right
            previousButton = StripButton(this@WritingActivity, StripButton.Glyph.PREVIOUS, "Previous page").apply { setOnClickListener { turn(forward = false) } }
            nextButton = StripButton(this@WritingActivity, StripButton.Glyph.NEXT, "Next page").apply { setOnClickListener { turn(forward = true) } }
            addView(previousButton, square())
            addView(indicator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = gap })
            addView(nextButton, square().apply { marginEnd = 0 })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, stripHeight))
            // One hairline between the strip and the page.
            addView(View(this@WritingActivity).apply { setBackgroundColor(RULE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2))
            addView(pages, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // Android 15+ draws apps under the status bar. Keep the strip below it.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })

        session = DocumentSession(
            pdf = File(path),
            journalDir = File(filesDir, "journals"),
            open = { PdfFile.open(it) },
            scheduler = WorkerScheduler(),
            events = { e -> runOnUiThread { show(e) } },
        )
        session.start()

        pages.requestRender = { page, width ->
            session.runOnDocument { pdf ->
                try {
                    val bitmap = (pdf as PdfFile).render(page, width)
                    runOnUiThread { pages.pageRendered(page, width, bitmap) }
                } catch (e: Exception) {
                    Log.w(TAG, "render failed page=$page", e)
                    runOnUiThread { pages.renderFailed(page, width) }
                }
            }
        }
        pages.onViewportChanged = {
            pageMaybeTurned()
            updateIndicator()
            updatePenWidth()
            publishPenMapping()
            if (documentShown) pen.setLimit(Rect(0, 0, pages.width, pages.height))
        }
        session.runOnDocument { pdf ->
            val file = pdf as PdfFile
            val sizes = (0 until file.pageCount).map { file.displaySize(it).let { s -> SizePt(s.width, s.height) } }
            val transforms = (0 until file.pageCount).map { file.pdfToDisplay(it) }
            runOnUiThread {
                pageCount = sizes.size
                pdfToDisplay = transforms
                displayToPdf = transforms.map { it.inverse() }
                pages.setPages(sizes, transforms, lastPages.get(path))
                pages.post {
                    documentShown = true
                    publishPenMapping()
                    penOn()
                }
            }
        }

        pen = PenInput(
            pages,
            onPenDown = {
                strokeMapping = penMapping
                strokeChoice = penChoice
                onyxOwnsStroke = !screenHasPen
                handler.removeCallbacks(retryShow)
            },
            onStroke = { points -> onPenStroke(points) },
            onEraseTrail = { points -> onEraseTrail(points) },
            onPenUp = {
                if (showPending) retryShowWhenIdle()
                if (pauseWhenLifted) runOnUiThread { toolStateChanged() }
            },
        )
        pages.onStylusHover = { button ->
            if (button != hoverButton) {
                hoverButton = button
                publishPenChoice()
            }
        }
        pages.onStylusDown = { button ->
            hoverButton = button
            publishPenChoice()
            strokeChoice = penChoice
        }
        pages.onFingerGestureStart = {
            Log.i(TAG, "fingers down: pause fast-ink")
            handler.removeCallbacks(resumeFastInk) // a show's resume mustn't land mid-gesture
            pausePenAndRedraw()
        }
        pages.onFingerGestureEnd = { moved ->
            if (moved) {
                // Wipe fast-ink's leftover copy of the strokes, then hand the pen back.
                Log.i(TAG, "fingers up after moving: clean refresh, then resume fast-ink")
                pages.invalidate()
                pages.post {
                    pen.cleanRefresh(pages)
                    handler.postDelayed(resumeFastInk, CLEAN_REFRESH_MS)
                }
            } else {
                Log.i(TAG, "fingers up: resume fast-ink")
                resumeFastInk.run()
            }
        }
        pages.onPageShown = { page ->
            if (page == pages.viewport?.page) {
                layer.pageRedrawn()
                showWithoutFastInk()
            }
        }
        choose(Tool.PEN)
    }

    override fun onResume() {
        super.onResume()
        if (documentShown) pages.post { penOn() }
        if (showPending) retryShowWhenIdle()
    }

    /** The pen is limited to the page area, below the strip. Fast-ink stays off if a tool needs it off. */
    private fun penOn() {
        pen.on(Rect(0, 0, pages.width, pages.height))
        if (!fastInkWanted()) pen.pause()
    }

    /**
     * The one place that decides whether fast-ink should be on: the pages are up, no dialog
     * covers them, and the tool draws with fast-ink ([ToolState.fastInkOn]).
     */
    private fun fastInkWanted() = documentShown && !dialogOpen && toolState.fastInkOn()

    private fun publishPenMapping() {
        val v = pages.viewport ?: return
        if (displayToPdf.isEmpty()) return
        penMapping = v.page to v.pageToScreen().inverse().then(displayToPdf[v.page])
    }

    /** UI thread: whenever the chosen tool or the held button changes. Also sets the live line to match. */
    private fun publishPenChoice() {
        penChoice = hoverButton to toolState.strokeTool(hoverButton)
        updatePenWidth()
    }

    /**
     * A finished pen stroke, on Onyx's thread. What it does depends on the tool it got at
     * touch-down. Ink goes into the journal straight away (spec §7.3). The page isn't redrawn:
     * Onyx's ink stays on screen, and the page view keeps its own copy of the stroke for
     * whenever it redraws. Redrawing here used to pause fast-ink and cut off the next stroke
     * (owner's first writing session).
     */
    private fun onPenStroke(points: List<FloatArray>) {
        val (page, screenToPdf) = strokeMapping ?: penMapping ?: return
        val (button, tool) = strokeChoice ?: penChoice
        if (!onyxOwnsStroke) {
            // The screen had the pen (a selection, snip or paste) when this stroke began, and it
            // has handled it already.
            Log.i(TAG, "stroke from Onyx ignored: the screen had the pen")
            return
        }
        Log.i(TAG, "stroke page=$page tool=$tool button=$button points=${points.size}")
        when (tool) {
            Tool.PEN, Tool.HIGHLIGHTER -> {
                val kind = if (tool == Tool.HIGHLIGHTER) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
                val (_, stroke) = PenPoints.toStroke(kind, points) { x, y -> page to screenToPdf.map(Pt(x, y)) } ?: return
                session.addStroke(page, stroke)
                runOnUiThread { pages.addStroke(page, stroke) }
            }
            Tool.ERASER -> erase(page, points.map { screenToPdf.map(Pt(it[0], it[1])) })
            Tool.LASSO -> lasso(page, points.map { screenToPdf.map(Pt(it[0], it[1])) })
            Tool.SNIP -> Unit // fast-ink is off while snipping (ToolState.fastInkOn): the selection layer has it
        }
    }

    /** Button 2's trail, from Onyx's erase callbacks, on Onyx's thread. */
    private fun onEraseTrail(points: List<FloatArray>) {
        val (page, screenToPdf) = strokeMapping ?: penMapping ?: return
        if (!onyxOwnsStroke) return // the screen had the pen; it traced this itself
        Log.i(TAG, "stroke page=$page tool=${Tool.ERASER} button=${PenButton.ERASER_END} points=${points.size} (erase callback)")
        erase(page, points.map { screenToPdf.map(Pt(it[0], it[1])) })
    }

    /**
     * Removes what the [trail] (PDF space) touched once the pen has lifted (plan decision 5).
     * The page is then redrawn without it, which also wipes the grey trail. If nothing was
     * hit, no change comes, so the trail is wiped straight away.
     */
    private fun erase(page: Int, trail: List<Pt>) {
        val started = SystemClock.uptimeMillis()
        session.erase(page, trail) { hits ->
            Log.i(TAG, "erase page=$page hits=$hits ms=${SystemClock.uptimeMillis() - started}")
            // Posted before the PageChanged that follows a hit, so it's marked in time.
            runOnUiThread { if (hits > 0) expectRedraw(page) else showWithoutFastInk() }
        }
    }

    /** Any thread. The items a lasso [loop] (PDF space) picks up become the selection. */
    private fun lasso(page: Int, loop: List<Pt>) {
        session.select(page, loop) { items ->
            Log.i(TAG, "lasso page=$page selected=${items.size}")
            runOnUiThread {
                // Nothing caught, or the page turned meanwhile: just wipe the grey loop.
                if (items.isEmpty() || page != pages.viewport?.page) showWithoutFastInk() else setSelection(page, items)
            }
        }
    }

    /** UI thread. [notShownYet]: the page doesn't show them there yet (a paste, a move), so preview them. */
    private fun setSelection(page: Int, items: List<Item>, notShownYet: Boolean = false) {
        selected = items
        selectedPage = page
        toolState.hasSelection = true
        layer.select(items, notShownYet)
        toolStateChanged()
    }

    /** UI thread. Lets go of the selection; the caller then calls [toolStateChanged]. */
    private fun letGo() {
        selected = emptyList()
        selectedPage = -1
        toolState.hasSelection = false
        layer.clearSelection()
    }

    /** UI thread. A new page on show lets go of the selection and cancels an armed paste. */
    private fun pageMaybeTurned() {
        val page = pages.viewport?.page ?: return
        if (page == shownPage) return
        shownPage = page
        if (!toolState.hasSelection && !toolState.pasteArmed) return
        letGo()
        toolState.pasteArmed = false
        toolStateChanged()
    }

    /**
     * The Paste button (Task 9 puts it in the strip; it shows while [ToolState.showPaste]):
     * arms a paste for the next pen tap on the page, or cancels one already armed.
     */
    fun togglePaste() {
        if (toolState.clipboard == null) return
        toolState.pasteArmed = !toolState.pasteArmed
        Log.i(TAG, "paste armed=${toolState.pasteArmed}")
        toolStateChanged()
    }

    /** What the selection layer reports, turned into changes. UI thread. */
    private val selectionListener = object : SelectionLayer.Listener {
        override fun moved(pdfTransform: Affine) {
            val page = selectedPage
            val before = selected
            val after = before.map { it.transformed(pdfTransform, pdfTransform.uniformScale()) }
            val done = recordFromScreen(ReplaceItems(page, before, after)) as? ReplaceItems ?: return
            Log.i(TAG, "moved page=$page items=${done.after.size} scale=${pdfTransform.uniformScale()}")
            setSelection(page, done.after, notShownYet = true)
        }

        override fun copy() {
            toolState.clipboard = Clipboard(selected, pdfToDisplay[selectedPage])
            Log.i(TAG, "copied page=$selectedPage items=${selected.size}")
            // Task 9: the strip shows Paste once toolState.showPaste is true.
        }

        override fun delete() {
            recordFromScreen(RemoveItems(selectedPage, selected))
            Log.i(TAG, "deleted page=$selectedPage items=${selected.size}")
            letGo()
            toolStateChanged()
        }

        override fun cleared() {
            letGo()
            toolStateChanged()
        }

        override fun snipped(displayBox: Box) {
            val page = pages.viewport?.page ?: return
            toolState.snipDone()
            toolChosen()
            captureSnip(page, displayBox)
        }

        override fun pasteAt(at: Pt) {
            val clip = toolState.clipboard ?: return
            val page = pages.viewport?.page ?: return
            toolState.pasteArmed = false
            val done = recordFromScreen(AddItems(page, clip.placedOn(displayToPdf[page], at))) as? AddItems
            Log.i(TAG, "pasted page=$page items=${done?.items?.size ?: 0}")
            if (done != null) setSelection(page, done.items, notShownYet = true) else toolStateChanged()
        }

        override fun traced(button: PenButton, points: List<Pt>) {
            val (page, screenToPdf) = penMapping ?: return
            val tool = toolState.strokeTool(button)
            Log.i(TAG, "stroke page=$page tool=$tool button=$button points=${points.size} (traced, fast-ink off)")
            val pdf = points.map { screenToPdf.map(it) }
            when (tool) {
                Tool.ERASER -> erase(page, pdf)
                Tool.LASSO -> lasso(page, pdf)
                else -> Unit
            }
        }
    }

    /**
     * The snip's picture (plan decision 9): the region as shown, ink and all, at 2 pixels per
     * point, but at most 2000 pixels on the long side, as PNG. It's rendered straight at the
     * smaller scale rather than rendered large and scaled down. Its box is the region in PDF
     * space, and it's upright on a page turned the way this one is (Ruling R1).
     */
    private fun captureSnip(page: Int, displayBox: Box) {
        val started = SystemClock.uptimeMillis()
        session.runOnDocument { pdf ->
            try {
                val file = pdf as PdfFile
                val longSide = max(displayBox.x1 - displayBox.x0, displayBox.y1 - displayBox.y0)
                val bitmap = file.renderRegion(page, displayBox, min(SNIP_PX_PER_PT, SNIP_MAX_PX / longSide))
                val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                val size = "${bitmap.width}x${bitmap.height}"
                bitmap.recycle()
                val toDisplay = file.pdfToDisplay(page)
                val toPdf = toDisplay.inverse()
                val corners = with(displayBox) { listOf(Pt(x0, y0), Pt(x1, y0), Pt(x0, y1), Pt(x1, y1)) }.map { toPdf.map(it) }
                val box = Box(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
                val item = ImageItem(ItemIds.next(), box, file.rotation(page) / 90, png)
                Log.i(TAG, "snip page=$page px=$size bytes=${png.size} ms=${SystemClock.uptimeMillis() - started}")
                runOnUiThread { toolState.clipboard = Clipboard(listOf(item), toDisplay) }
            } catch (e: Exception) {
                Log.w(TAG, "snip failed page=$page", e)
                runOnUiThread { toast("Couldn't take the snip.") }
            }
        }
    }

    /**
     * Records a change made from the screen, marking its pages to be redrawn first (see
     * [expectRedraw]). Returns the change as journaled: keep the selection on its items.
     */
    private fun recordFromScreen(change: Change): Change? {
        val fresh = change.pages.filter { it !in redrawWhenChanged }
        change.pages.forEach { expectRedraw(it) }
        val done = session.record(change)
        // Nothing was recorded, so no PageChanged comes for it.
        if (done == null) redrawWhenChanged -= fresh.toSet()
        return done
    }

    /**
     * UI thread, after any change to [toolState]. The selection layer follows it, and fast-ink
     * goes off while a tool needs it off (a selection, a snip, an armed paste), or comes back.
     * Never while the pen is down: then the lift does it.
     */
    private fun toolStateChanged() {
        layer.snipping = toolState.chosen == Tool.SNIP
        layer.pasteArmed = toolState.pasteArmed
        handler.removeCallbacks(resumeFastInk)
        pages.invalidate()
        if (toolState.fastInkOn()) {
            pauseWhenLifted = false
            // Once the screen has drawn the page without the box or rectangle.
            handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
            return
        }
        pages.penHandler = screenPen
        screenHasPen = true
        // Set before the check, so a lift from here on sees it and does the pause.
        pauseWhenLifted = true
        if (pen.isWriting) {
            Log.i(TAG, "fast-ink off for the tool once the pen lifts")
            return
        }
        pauseWhenLifted = false
        Log.i(TAG, "fast-ink off for the tool")
        pen.pause()
    }

    /** Pen events while fast-ink is off for a tool. UI thread. */
    private val screenPen: (MotionEvent) -> Boolean = { e ->
        val m = e.actionMasked
        if (m == MotionEvent.ACTION_DOWN) {
            screenPenDown = true
            handler.removeCallbacks(retryShow)
        }
        if (m == MotionEvent.ACTION_UP || m == MotionEvent.ACTION_CANCEL) screenPenDown = false
        val frame = pages.frame()
        val used = frame != null && layer.onPen(e, hoverButton, frame, pages.width)
        if (m == MotionEvent.ACTION_UP || m == MotionEvent.ACTION_CANCEL) {
            if (showPending) retryShowWhenIdle()
            // The stroke may have let go of the tool (a tap outside, a snip, a paste).
            if (toolState.fastInkOn()) {
                handler.removeCallbacks(resumeFastInk)
                handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
            }
        }
        used
    }

    /**
     * [page] has a change on its way that the page view can't show by itself, so redraw it
     * when the change reaches the PDF (the [SessionEvent.PageChanged] handler in [show]).
     * Every change made from the screen must call this: erases now, and in Tasks 8–9 moves,
     * resizes, deletes, pastes, undo and redo, and rotations. Plain pen strokes must not: the
     * page view already shows them, and a redraw would pause fast-ink after each one. UI thread.
     */
    private fun expectRedraw(page: Int) {
        redrawWhenChanged += page
    }

    /** "Go to page": type a number, which is quicker than tapping › through a long deck. */
    private fun askForPage() {
        val v = pages.viewport ?: return
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 to $pageCount"
            textSize = 22f
            setSelectAllOnFocus(true)
            setText((v.page + 1).toString())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(field)
            .setPositiveButton("Go") { _, _ ->
                val wanted = field.text.toString().toIntOrNull() ?: return@setPositiveButton
                Log.i(TAG, "go to page $wanted")
                pages.goTo(wanted.coerceIn(1, pageCount) - 1)
                showWithoutFastInk()
            }
            .setNegativeButton("Cancel", null)
            .create()
        // Fast-ink would freeze the dialog on screen, so it's off while the dialog is open.
        dialogOpen = true
        pen.pause()
        dialog.setOnDismissListener {
            dialogOpen = false
            resumeFastInk.run()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    private fun turn(forward: Boolean) {
        Log.i(TAG, "turn forward=$forward")
        if (pages.turn(forward)) showWithoutFastInk()
    }

    /**
     * The screen ignores normal drawing while fast-ink is on, so a new page needs fast-ink
     * paused for a moment to appear. Never while the pen is down: that would cut the stroke.
     */
    private fun showWithoutFastInk() {
        if (pen.isWriting) {
            Log.i(TAG, "show skipped: pen is down")
            showPending = true
            // The pen may have lifted while this was being decided, too late for its lift to see it.
            if (!pen.isWriting) retryShowWhenIdle()
            return
        }
        showPending = false
        handler.removeCallbacks(retryShow)
        Log.i(TAG, "show: pause fast-ink")
        pausePenAndRedraw()
        handler.removeCallbacks(resumeFastInk)
        handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
    }

    /** Any thread. Starts (or restarts) the wait before a skipped show runs. */
    private fun retryShowWhenIdle() {
        handler.removeCallbacks(retryShow)
        handler.postDelayed(retryShow, SHOW_RETRY_IDLE_MS)
    }

    /**
     * Pausing fast-ink hands the screen back to normal drawing, which shows the page view's
     * last picture. That picture may be from before the latest strokes, because the screen
     * ignores normal drawing while fast-ink is on. So draw it again right away, with the strokes.
     */
    private fun pausePenAndRedraw() {
        pen.pause()
        pages.invalidate()
    }

    /** Choosing a tool lets go of the selection and cancels an armed paste. */
    private fun choose(newTool: Tool) {
        if (toolState.hasSelection) letGo()
        toolState.pasteArmed = false
        toolState.chosen = newTool
        toolChosen()
    }

    /** The strip, the live line and the pen's next stroke follow [ToolState.chosen]. */
    private fun toolChosen() {
        penButton.isSelected = toolState.chosen == Tool.PEN
        highlighterButton.isSelected = toolState.chosen == Tool.HIGHLIGHTER
        publishPenChoice()
        toolStateChanged()
    }

    /**
     * Onyx's live line for the tool the pen would get now: grey from the first point for a
     * lasso drawn with button 1. Ink is as wide as the saved ink will be, at this zoom.
     */
    private fun updatePenWidth() {
        val v = pages.viewport
        pen.setLiveLine(liveLine(penChoice.second), v?.pxPerPt() ?: 1f)
    }

    override fun onPause() {
        super.onPause()
        // Nothing may turn fast-ink back on in the background; onResume starts it afresh.
        handler.removeCallbacks(retryShow)
        handler.removeCallbacks(resumeFastInk)
        pen.off()
        pages.viewport?.let { lastPages.set(path, it.page) }
        // Going to the background or leaving: save now, not in a few seconds (spec §7.2).
        session.saveNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::session.isInitialized) session.close()
    }

    private fun updateIndicator() {
        val v = pages.viewport ?: return
        indicator.text = "${v.page + 1} / $pageCount"
        indicator.contentDescription = "Page ${v.page + 1} of $pageCount. Tap to go to a page."
        previousButton.isEnabled = v.page > 0
        nextButton.isEnabled = v.page < pageCount - 1
    }

    private fun show(event: SessionEvent) {
        when (event) {
            SessionEvent.Saved -> Unit // saving is silent when it works
            is SessionEvent.SaveFailed -> toast("Couldn't save: ${event.message}. Your writing is kept safe and will be saved again.")
            SessionEvent.ChangedOnDisk -> toast("This file was changed elsewhere, so it isn't being saved into. Your writing is kept safe.")
            SessionEvent.WritingStopped -> toast("A change couldn't be written into this file, so it isn't being saved into. Your writing is kept safe in the app.")
            SessionEvent.NeedsRepairDecision -> askAboutRepair()
            is SessionEvent.Replayed -> toast("Recovered ${event.count} change(s) that hadn't been saved.")
            is SessionEvent.PageChanged -> event.pages.forEach { page ->
                // Redrawn the usual way: once rendered, onPageShown shows it without fast-ink.
                if (redrawWhenChanged.remove(page)) pages.pageChanged(page)
            }
            is SessionEvent.HistoryChanged -> Unit // the undo and redo buttons come in Task 9
            is SessionEvent.EarlierWritingKept -> toast("Unsaved writing from before this file changed was kept safe.")
            is SessionEvent.SavedToCopy -> toast("Saved to ${event.copy.name}.")
            is SessionEvent.OpenFailed -> {
                toast(event.message)
                finish()
            }
        }
    }

    /** Spec §7.3: a repaired file is never written to until the owner chooses. */
    private fun askAboutRepair() {
        AlertDialog.Builder(this)
            .setTitle("This file was damaged")
            .setMessage("It was repaired when it opened. Saving into it would replace the original with the repaired version.")
            .setPositiveButton("Save into this file") { _, _ -> session.saveIntoRepairedFile() }
            .setNegativeButton("Save my writing to a copy") { _, _ -> session.saveRepairedToCopy() }
            .setCancelable(false)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PATH = "path"
        const val SHOW_PAUSE_MS = 150L

        /** How long the pen must rest before a show skipped while writing is tried again. */
        const val SHOW_RETRY_IDLE_MS = 500L

        /** Snips (plan decision 9): 2 pixels per point, at most 2000 pixels on the long side. */
        const val SNIP_PX_PER_PT = 2f
        const val SNIP_MAX_PX = 2000f

        /** The hairline under the strip. */
        private val RULE = Color.rgb(200, 200, 200)

        /** A full e-ink refresh takes about this long; the pen comes back after it. */
        const val CLEAN_REFRESH_MS = 450L
        private const val TAG = "Notes"
    }
}
