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
import android.widget.PopupMenu
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
import com.fzizzle.epapernotes.ui.StripButton.Glyph
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The writing screen (spec §6.2): one page at a time fills the screen under one thin strip:
 * ← library, the tools, undo and redo, Paste (only once there's something to paste), the ⋯
 * page menu, and ‹ page indicator ›.
 */
class WritingActivity : Activity() {

    private lateinit var session: DocumentSession
    private lateinit var pages: PageView
    private lateinit var indicator: TextView
    private lateinit var lastPages: LastPageStore
    /** The file being written into. It changes once, if the owner saves a damaged file's writing to a copy. */
    private lateinit var path: String
    private var pageCount = 0
    private lateinit var pen: PenInput
    private lateinit var toolButtons: Map<Tool, StripButton>
    private lateinit var undoButton: StripButton
    private lateinit var redoButton: StripButton
    private lateinit var pasteButton: StripButton
    private lateinit var menuButton: StripButton
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

    /** An undo or redo changed this page, not the one on show: go there once it's changed (decision 10). UI thread. */
    private var goToWhenChanged = NO_PAGE

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

    /**
     * A moved or pasted selection is previewed grey until a rendering asked for after its
     * change was recorded is on screen: one at this ink version or later. The worker handles
     * things in order, so any rendering asked for after `record` contains the change.
     */
    private var previewShownFrom = 0

    /** The page last on show: turning it lets go of the selection and an armed paste. */
    private var shownPage = -1
    private var dialogOpen = false

    /**
     * True while the screen, not Onyx, follows a pen stroke (fast-ink off for a tool). Fast-ink
     * must not come back then, or Onyx would pick the stroke up halfway. UI thread.
     */
    private var screenPenDown = false

    /** Whether the screen follows the pen stroke under way, rather than Onyx (see [screenPen]). UI thread. */
    private var screenStroke = false

    /** [penMapping] when the screen's stroke began: a traced stroke is placed with the view as it was then. UI thread. */
    private var screenStrokeMapping: Pair<Int, Affine>? = null

    /**
     * True while [PageView.penHandler] is set, which is only while fast-ink is paused for a
     * tool. Onyx's thread reads it at pen-down.
     */
    @Volatile private var screenHasPen = false

    /** Whether the app records the stroke Onyx is following (see where it's set, in onPenDown). */
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
        pages.onFingerTap = { x, y -> pages.frame()?.let { layer.onFingerTap(x, y, it) } }
        val gap = stripHeight / 8
        // In the spec's order (§6.2), a wider gap after ← and after the tools.
        val strip = StripRow(this, side = stripHeight - 2 * gap, gap = gap).apply { setBackgroundColor(Color.WHITE) }
        fun button(glyph: Glyph, description: String, gaps: Int = 1, onClick: () -> Unit) =
            StripButton(this, glyph, description).also {
                it.setOnClickListener { onClick() }
                strip.addSquare(it, gaps)
            }
        button(Glyph.BACK, "Back to the list", gaps = 3) { leave() }
        toolButtons = mapOf(
            Tool.PEN to button(Glyph.PEN, "Pen") { choose(Tool.PEN) },
            Tool.HIGHLIGHTER to button(Glyph.HIGHLIGHTER, "Highlighter") { choose(Tool.HIGHLIGHTER) },
            Tool.ERASER to button(Glyph.ERASER, "Eraser") { choose(Tool.ERASER) },
            Tool.LASSO to button(Glyph.LASSO, "Lasso select") { choose(Tool.LASSO) },
            Tool.SNIP to button(Glyph.SNIP, "Snip", gaps = 3) { choose(Tool.SNIP) },
        )
        undoButton = button(Glyph.UNDO, "Undo") { undoOrRedo(undo = true) }.apply { isEnabled = false }
        redoButton = button(Glyph.REDO, "Redo") { undoOrRedo(undo = false) }.apply { isEnabled = false }
        pasteButton = button(Glyph.PASTE, "Paste") { togglePaste() }.apply { visibility = View.GONE }
        menuButton = button(Glyph.MENU, "Page menu") { openPageMenu() }
        strip.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) // pushes the page controls right
        previousButton = button(Glyph.PREVIOUS, "Previous page") { turn(forward = false) }
        strip.addView(indicator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = gap })
        nextButton = button(Glyph.NEXT, "Next page", gaps = 0) { turn(forward = true) }
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
            events = { e ->
                // On the worker. After leaving, this is how a check sees the last save land.
                if (e == SessionEvent.Saved) Log.i(TAG, "saved")
                runOnUiThread { show(e) }
            },
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
        layOutPages(startPage = lastPages.get(path)) {
            pages.post {
                documentShown = true
                publishPenMapping()
                penOn()
            }
        }

        pen = PenInput(
            pages,
            onPenDown = {
                strokeMapping = penMapping
                strokeChoice = penChoice
                // The rule: whatever Onyx draws, the app records. Onyx draws while fast-ink is
                // on, whoever is meant to have the pen, so only a stroke that lands with fast-ink
                // paused and the screen holding the pen is left to the screen, which traces it.
                onyxOwnsStroke = pen.drawsInk || !screenHasPen
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
            // Moving a zoomed page with one finger changes where the page is without telling
            // onViewportChanged (that would call Onyx on every finger movement). Fast-ink is
            // off until here anyway, so telling the pen now is in time for the next stroke.
            publishPenMapping()
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
        pages.onPageShown = { page, version ->
            if (page == pages.viewport?.page) {
                if (version >= previewShownFrom) layer.pageRedrawn()
                showWithoutFastInk()
            }
        }
        choose(Tool.PEN)
    }

    /**
     * Reads every page's size and placement on the worker, then lays the pages out at
     * [startPage], or the page on show: at the start, and again once a page has turned. A
     * rotation replayed at open can come before the first layout, so then it's the last page open.
     */
    private fun layOutPages(startPage: Int? = null, then: () -> Unit) {
        session.runOnDocument { pdf ->
            val file = pdf as PdfFile
            val sizes = (0 until file.pageCount).map { file.displaySize(it).let { s -> SizePt(s.width, s.height) } }
            val transforms = (0 until file.pageCount).map { file.pdfToDisplay(it) }
            runOnUiThread {
                // A page that turned has renderings the wrong way round.
                pdfToDisplay.indices.filter { it < transforms.size && transforms[it] != pdfToDisplay[it] }.forEach { pages.pageRotated(it) }
                pageCount = sizes.size
                pdfToDisplay = transforms
                displayToPdf = transforms.map { it.inverse() }
                pages.setPages(sizes, transforms, startPage ?: pages.viewport?.page ?: lastPages.get(path))
                then()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (documentShown) pages.post { penOn() }
        if (showPending) retryShowWhenIdle()
    }

    /** The pen is limited to the page area, below the strip. Fast-ink stays off if a tool needs it off. */
    private fun penOn() {
        pen.on(Rect(0, 0, pages.width, pages.height))
        if (!fastInkWanted()) {
            pen.pause()
            // A tool's pause waiting for a lift that never came (the app was left mid-stroke):
            // the screen takes the pen now, as the lift would have done.
            if (!toolState.fastInkOn()) {
                pauseWhenLifted = false
                pages.penHandler = screenPen
                screenHasPen = true
            }
        } else {
            // A resume that onPause cancelled can leave the screen holding the pen.
            pages.penHandler = null
            screenHasPen = false
        }
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
        Log.i(TAG, "pen mapping page=${v.page} zoom=${v.zoom}")
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
            // Fast-ink was paused and the screen had the pen (a selection, snip or paste) when
            // this stroke began: Onyx drew nothing, and the screen has handled it already.
            Log.i(TAG, "stroke from Onyx ignored: the screen had the pen")
            return
        }
        Log.i(TAG, "stroke page=$page tool=$tool button=$button points=${points.size}")
        when (tool) {
            Tool.PEN, Tool.HIGHLIGHTER -> write(page, screenToPdf, tool, points)
            Tool.ERASER -> erase(page, points.map { screenToPdf.map(Pt(it[0], it[1])) })
            Tool.LASSO -> lasso(page, points.map { screenToPdf.map(Pt(it[0], it[1])) })
            Tool.SNIP -> Unit // fast-ink is off while snipping (ToolState.fastInkOn): the selection layer has it
        }
    }

    /**
     * A pen or highlighter stroke, `[x, y, rawPressure]` in screen pixels, recorded as ink: into
     * the journal straight away, then onto the page view's own copy. Any thread.
     */
    private fun write(page: Int, screenToPdf: Affine, tool: Tool, points: List<FloatArray>) {
        val kind = if (tool == Tool.HIGHLIGHTER) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
        val (_, stroke) = PenPoints.toStroke(kind, points) { x, y -> page to screenToPdf.map(Pt(x, y)) } ?: return
        session.addStroke(page, stroke)
        runOnUiThread { pages.addStroke(page, stroke) }
    }

    /** Button 2's trail, from Onyx's erase callbacks, on Onyx's thread. */
    private fun onEraseTrail(points: List<FloatArray>) {
        val (page, screenToPdf) = strokeMapping ?: penMapping ?: return
        if (!onyxOwnsStroke) return // fast-ink was paused and the screen had the pen; it traced this itself
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
        // Asked for after the change was recorded means a version above today's (see previewShownFrom).
        if (notShownYet) previewShownFrom = pages.inkVersion(page) + 1
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
        letGoOfAll()
    }

    /** UI thread. Lets go of the selection and cancels an armed paste, if there's either. */
    private fun letGoOfAll() {
        if (!toolState.hasSelection && !toolState.pasteArmed) return
        letGo()
        toolState.pasteArmed = false
        toolStateChanged()
    }

    /**
     * The Paste button (it shows while [ToolState.showPaste]): arms a paste for the next pen
     * tap on the page, or cancels one already armed.
     */
    private fun togglePaste() {
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
            updateStrip() // Paste appears
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

        override fun traced(button: PenButton, points: List<FloatArray>) {
            val (page, screenToPdf) = screenStrokeMapping ?: penMapping ?: return
            val tool = toolState.strokeTool(button)
            Log.i(TAG, "stroke page=$page tool=$tool button=$button points=${points.size} (traced, fast-ink off)")
            val pdf by lazy { points.map { screenToPdf.map(Pt(it[0], it[1])) } }
            when (tool) {
                // Written in the moment before fast-ink came back, say just after letting go of
                // a selection: ink like any other, recorded the way an Onyx stroke is.
                Tool.PEN, Tool.HIGHLIGHTER -> write(page, screenToPdf, tool, points)
                Tool.ERASER -> erase(page, pdf)
                Tool.LASSO -> lasso(page, pdf)
                Tool.SNIP -> Unit // the snip tool never traces: the layer has its rectangle
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
                runOnUiThread {
                    toolState.clipboard = Clipboard(listOf(item), toDisplay)
                    updateStrip() // Paste appears
                }
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
        updateStrip()
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
        // Set before the check, so a lift from here on sees it and does the pause.
        pauseWhenLifted = true
        if (pen.isWriting) {
            // Onyx keeps the pen, and the next stroke too if it lands before the pause: Onyx
            // draws it, so it must reach onPenStroke, not the screen.
            Log.i(TAG, "fast-ink off for the tool once the pen lifts")
            return
        }
        pauseWhenLifted = false
        Log.i(TAG, "fast-ink off for the tool")
        pen.pause()
        // Only now, with fast-ink paused, does the screen take the pen.
        pages.penHandler = screenPen
        screenHasPen = true
    }

    /**
     * Pen events while fast-ink is off for a tool. UI thread. A stroke that lands while Onyx
     * draws after all (fast-ink back on with the screen still holding the pen) is Onyx's, and
     * Onyx's callback records it (see onPenDown); the screen leaves it alone, or it would count twice.
     */
    private val screenPen: (MotionEvent) -> Boolean = screen@{ e ->
        val m = e.actionMasked
        if (m == MotionEvent.ACTION_DOWN) screenStroke = !pen.drawsInk
        if (!screenStroke) return@screen false
        if (m == MotionEvent.ACTION_DOWN) {
            screenPenDown = true
            screenStrokeMapping = penMapping
            handler.removeCallbacks(retryShow)
        }
        if (m == MotionEvent.ACTION_UP || m == MotionEvent.ACTION_CANCEL) screenPenDown = false
        val frame = pages.frame()
        val used = frame != null && layer.onPen(e, hoverButton, frame)
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
     * Every change made from the screen must call this: erases, moves, resizes, deletes,
     * pastes, undo and redo ([undoOrRedo]), and rotations ([rotate]). Plain pen strokes must
     * not: the page view already shows them, and a redraw would pause fast-ink after each one.
     * UI thread.
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

    /**
     * Undo or redo (spec §6.3). The session says what it recorded, so its pages are marked
     * for a redraw before its PageChanged can come: that comes through the UI thread's queue,
     * behind this. A change on another page is undone there, in view (decision 10): the page
     * is gone to once the change has reached the PDF ([pagesChanged]), so it shows it.
     */
    private fun undoOrRedo(undo: Boolean) {
        letGoOfAll() // the selection may be what's undone
        val change = (if (undo) session.undo() else session.redo()) ?: return
        Log.i(TAG, "${if (undo) "undo" else "redo"} pages=${change.pages}")
        change.pages.forEach { expectRedraw(it) }
        val here = pages.viewport?.page ?: return
        goToWhenChanged = if (here in change.pages) NO_PAGE else change.pages.min()
    }

    /** ⋯: the page menu (spec §6.4). Fast-ink is off while it's open, as for "Go to page". */
    private fun openPageMenu() {
        if (pen.isWriting) return // pausing fast-ink now would cut the stroke
        val menu = PopupMenu(this, menuButton)
        menu.menu.add("Rotate this page").setOnMenuItemClickListener { rotate(all = false); true }
        menu.menu.add("Rotate all pages").setOnMenuItemClickListener { rotate(all = true); true }
        dialogOpen = true
        pen.pause()
        menu.setOnDismissListener {
            dialogOpen = false
            resumeFastInk.run()
        }
        menu.show()
    }

    /** Turns the page on show, or every page, 90° clockwise from where it is (spec §6.4), as one undo step. */
    private fun rotate(all: Boolean) {
        val here = pages.viewport?.page ?: return
        letGoOfAll()
        val turning = if (all) (0 until pageCount).toList() else listOf(here)
        Log.i(TAG, "rotate ${if (all) "all $pageCount pages" else "page ${here + 1}"}")
        turning.forEach { expectRedraw(it) }
        session.rotate(turning)
    }

    /** UI thread: changes have reached the PDF on [changed] pages. */
    private fun pagesChanged(changed: Set<Int>) {
        // Redrawn the usual way: once rendered, onPageShown shows it without fast-ink.
        changed.forEach { if (redrawWhenChanged.remove(it)) pages.pageChanged(it) }
        val target = goToWhenChanged
        if (target !in changed) return
        goToWhenChanged = NO_PAGE
        Log.i(TAG, "go to page ${target + 1}: the undo or redo changed it")
        pages.goTo(target)
        showWithoutFastInk()
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
        publishPenChoice()
        toolStateChanged()
    }

    /** The chosen tool inverted, and Paste there once there's something to paste, inverted while armed. */
    private fun updateStrip() {
        toolButtons.forEach { (tool, button) -> button.isSelected = toolState.chosen == tool }
        pasteButton.visibility = if (toolState.showPaste) View.VISIBLE else View.GONE
        pasteButton.isSelected = toolState.pasteArmed
    }

    /**
     * Onyx's live line for the tool the pen would get now: grey from the first point for a
     * lasso drawn with button 1. Ink is as wide as the saved ink will be, at this zoom.
     */
    private fun updatePenWidth() {
        val v = pages.viewport
        pen.setLiveLine(liveLine(penChoice.second), v?.pxPerPt() ?: 1f)
    }

    /** ← in the strip. Leaving is timed from here or the back key (the "leave:" logs). */
    private fun leave() {
        Log.i(TAG, "leave: back")
        finish()
    }

    @Deprecated("Logged for the leave timings; then the usual back.")
    override fun onBackPressed() {
        Log.i(TAG, "leave: back")
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        val start = SystemClock.uptimeMillis()
        // Nothing may turn fast-ink back on in the background; onResume starts it afresh.
        handler.removeCallbacks(retryShow)
        handler.removeCallbacks(resumeFastInk)
        pen.off()
        val penOff = SystemClock.uptimeMillis()
        pages.viewport?.let { lastPages.set(path, it.page) }
        // Going to the background or leaving: save now, not in a few seconds (spec §7.2).
        session.saveNow()
        val end = SystemClock.uptimeMillis()
        Log.i(TAG, "${if (isFinishing) "leave: " else ""}pause ms=${end - start} pen-off=${penOff - start} save-request=${end - penOff}")
    }

    override fun onDestroy() {
        super.onDestroy()
        val start = SystemClock.uptimeMillis()
        handler.removeCallbacksAndMessages(null)
        if (::session.isInitialized) session.close()
        Log.i(TAG, "leave: destroy ms=${SystemClock.uptimeMillis() - start}")
    }

    private fun updateIndicator() {
        val v = pages.viewport ?: return
        indicator.text = "${v.page + 1} / $pageCount"
        indicator.contentDescription = "Page ${v.page + 1} of $pageCount. Tap to go to a page."
        previousButton.isEnabled = v.page > 0
        nextButton.isEnabled = v.page < pageCount - 1
    }

    private fun show(event: SessionEvent) {
        // The last save lands after the screen is left, so events still come once it's closing
        // or closed. Messages are still shown then: a failed save must never be silent. But
        // nothing touches the screen any more, and a question shown on a closed screen crashes.
        val needsTheScreen = event is SessionEvent.NeedsRepairDecision || event is SessionEvent.InkRedrawn ||
            event is SessionEvent.PageChanged || event is SessionEvent.HistoryChanged
        if (needsTheScreen && (isFinishing || isDestroyed)) return
        when (event) {
            SessionEvent.Saved -> Unit // saving is silent when it works
            is SessionEvent.SaveFailed -> toast("Couldn't save: ${event.message}. Your writing is kept safe and will be saved again.")
            SessionEvent.JournalFailed -> toast("Storage is full: writing can't be kept safe until there's room.")
            SessionEvent.ChangedOnDisk -> toast("This file was changed elsewhere, so it isn't being saved into. Your writing is kept safe.")
            SessionEvent.WritingStopped -> toast("A change couldn't be written into this file, so it isn't being saved into. Your writing is kept safe in the app.")
            SessionEvent.NeedsRepairDecision -> askAboutRepair()
            is SessionEvent.Replayed -> toast("Recovered ${event.count} change(s) that hadn't been saved.")
            // A page that turned has a new size and place on screen: lay the pages out again first.
            is SessionEvent.InkRedrawn -> pages.pageChanged(event.page) // drawn again if it's showing
            is SessionEvent.PageChanged -> if (event.rotated) layOutPages { pagesChanged(event.pages) } else pagesChanged(event.pages)
            is SessionEvent.HistoryChanged -> {
                undoButton.isEnabled = event.canUndo
                redoButton.isEnabled = event.canRedo
            }
            is SessionEvent.EarlierWritingKept -> toast("Unsaved writing from before this file changed was kept safe.")
            is SessionEvent.SavedToCopy -> {
                // The session writes into the copy from here on, so the copy is what "Continue"
                // and the remembered page must follow, not the damaged original.
                path = event.copy.path
                pages.viewport?.let { lastPages.set(path, it.page) }
                LibraryPrefs(this).lastOpened = event.copy
                toast("Saved to ${event.copy.name}.")
            }
            is SessionEvent.OpenFailed -> {
                toast(event.message)
                finish()
            }
        }
    }

    /** Spec §7.3: a repaired file is never written to until the owner chooses. */
    private fun askAboutRepair() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("This file was damaged")
            .setMessage("It was repaired when it opened. Saving into it would replace the original with the repaired version.")
            .setPositiveButton("Save into this file") { _, _ -> session.saveIntoRepairedFile() }
            .setNegativeButton("Save my writing to a copy") { _, _ -> session.saveRepairedToCopy() }
            .setCancelable(false)
            .create()
        // As for "Go to page": fast-ink would freeze the question on screen, and pen taps on its
        // buttons would mark the page underneath.
        dialogOpen = true
        pen.pause()
        dialog.setOnDismissListener {
            dialogOpen = false
            handler.removeCallbacks(resumeFastInk)
            handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
        }
        dialog.show()
    }

    /** With the app's context, not the screen's: a message can come after the screen has closed (see [show]). */
    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PATH = "path"
        private const val NO_PAGE = -1
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
