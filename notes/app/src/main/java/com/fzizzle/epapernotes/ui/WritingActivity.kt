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
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The writing screen (spec §6.2): one page at a time fills the screen under one thin strip:
 * ← library at the left; in the middle an island with the tools, undo and redo, and Paste (only
 * once there's something to paste); at the right the ⋯ page menu and ‹ page indicator ›. The
 * status bar is hidden here, so the strip sits at the very top of the screen.
 */
class WritingActivity : Activity() {

    private lateinit var session: DocumentSession
    private lateinit var pages: PageView
    private lateinit var indicator: TextView
    private lateinit var lastPages: LastPageStore

    private val readingPrefs by lazy { ReadingPrefs(this) }

    /**
     * How much darker page pictures are drawn ([Contrast]). Set on the UI thread from
     * [readingPrefs], read on the worker as each picture is made.
     */
    @Volatile private var contrast = Contrast.NORMAL

    /** Dark text as black (PageLook), like [contrast]: set on the UI thread, read on the worker. */
    @Volatile private var darkTextAsBlack = true

    /** Trim margins: pages fitted to their printed area, not the whole sheet. From [readingPrefs]. UI thread. */
    private var trimMargins = false

    /** A page's box to fit (ContentBounds.trim), in display points, and the page's ink version it was measured at. */
    private class PrintedArea(val box: Box, val inkVersion: Int)

    /**
     * Each page's printed area once measured, kept for the session, and the pages being
     * measured now. Only the page on show and its neighbours are measured, as pages are
     * arrived at ([measureAround]), not the whole document at open. UI thread.
     */
    private val printedAreas = HashMap<Int, PrintedArea>()
    private val measuring = HashSet<Int>()

    /** Pages whose measure has come back, with a box or without: their pictures may be made ahead (PageView.mayReadyAhead). */
    private val measuredOnce = HashSet<Int>()

    /**
     * True from a page being arrived at until the pen or a finger gesture first touches it.
     * Only until then may its printed area, arriving, refit it: a page never moves under the
     * pen. UI thread.
     */
    private var shownPageUntouched = false

    /** The file being written into. It changes once, if the owner saves a damaged file's writing to a copy. */
    private lateinit var path: String
    /** A strip button's side in pixels: the Paste bubble is as tall. */
    private var stripSide = 0
    /** The open file's name, in the strip beside ←. */
    private lateinit var fileName: TextView
    private var pageCount = 0
    private lateinit var pen: PenInput
    private lateinit var toolButtons: Map<Tool, StripButton>
    private lateinit var undoButton: StripButton
    private lateinit var redoButton: StripButton
    private lateinit var pasteButton: StripButton
    private lateinit var menuButton: StripButton
    private lateinit var island: IslandRow
    private lateinit var stripView: View
    private lateinit var previousButton: StripButton
    private lateinit var nextButton: StripButton
    private val toolState = ToolState()
    private lateinit var layer: SelectionLayer

    /** The button the pen last showed while hovering or landing. UI thread. */
    private var hoverButton = PenButton.NONE

    /** For each page, PDF space to display points, inverted: how a pen point finds its place in the PDF. */
    private var displayToPdf: List<Affine> = emptyList()
    private var pdfToDisplay: List<Affine> = emptyList()
    /** Each page's size as shown, as last laid out: kept so a rotation only reads the pages that turned. */
    private var pageSizes: List<SizePt> = emptyList()

    /**
     * The view changed while fingers were on the page, and Onyx hasn't been told the new line
     * width and writing area yet: [PageView.onFingerGestureEnd] tells it. UI thread.
     */
    private var penSettingsWaiting = false
    private val handler = Handler(Looper.getMainLooper())

    /** True once the pages are laid out. The pen stays off until then: a stroke would have nowhere to go. */
    private var documentShown = false

    /** True between onResume and onPause: the only time the pen may be switched on. UI thread. */
    private var resumed = false

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

    /** True from a finger gesture starting to pinch or move the page until its last finger lifts. */
    private var fingerGesture = false

    /** Fast-ink had to go off for a tool while the pen was down; the lift does it. */
    @Volatile private var pauseWhenLifted = false

    /**
     * Turns fast-ink back on if it's wanted now ([fastInkWanted]). Every resume goes through
     * here, so a show, a finger gesture or a dialog closing can't turn it on under a selection
     * or a dialog. Not while the screen has the pen down; its lift tries again.
     */
    private val resumeFastInk = Runnable {
        if (screenPenDown) return@Runnable
        // A page shown mid-pinch (its picture arrived) mustn't turn fast-ink on under the
        // fingers: the panel would stop following the page. The gesture's end resumes it.
        if (fingerGesture) return@Runnable
        if (toolState.fastInkOn()) {
            pages.penHandler = null
            screenHasPen = false
        }
        // Onyx writing means fast-ink is on already. Switching it on again when it was never
        // paused (choosing another tool) makes Onyx refresh the whole screen, so not then.
        if (fastInkWanted() && !pen.isWriting && !pen.drawsInk) {
            Log.i(TAG, "resume fast-ink")
            pen.resume()
        }
    }

    /**
     * The clean refresh after a zoom or a move, held for the sharp patch on its way
     * (PageView.sharpPatchComing): it goes when the patch is shown ([PageView.onPageShown]), or
     * after [PATCH_WAIT_MS] at the latest, so a zoom ends in one panel update, not two.
     */
    private var cleanRefreshHeld = false
    private val heldCleanRefresh = Runnable {
        cleanRefreshHeld = false
        pen.cleanRefresh(pages)
        handler.postDelayed(resumeFastInk, CLEAN_REFRESH_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        lastPages = LastPageStore(this)
        contrast = readingPrefs.contrast
        darkTextAsBlack = readingPrefs.darkTextAsBlack
        trimMargins = readingPrefs.trimMargins

        pages = PageView(this)
        indicator = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum" // even-width digits, so the number doesn't jiggle as pages turn
            setOnClickListener { askForPage() } // tap the number to jump to a page
        }
        // A little under 5% of the height (spec §6.2): the owner asked for it slightly thinner.
        val stripHeight = (resources.displayMetrics.heightPixels * 0.044f).toInt()
        // The selection's handles keep the size they had with the 5% strip.
        layer = SelectionLayer(handlePx = resources.displayMetrics.heightPixels * 0.05f / 3f, listener = selectionListener) { pages.invalidate() }
        pages.selectionLayer = layer
        pages.onFingerTap = { x, y -> pages.frame()?.let { layer.onFingerTap(x, y, it) } }
        pages.onFingerLongPress = { x, y -> offerPaste(x, y) }
        val gap = stripHeight / 8
        val side = stripHeight - 2 * gap
        stripSide = side
        val left = StripRow(this, side = side, gap = gap)
        island = IslandRow(this, side = side, gap = gap / 3)
        val right = StripRow(this, side = side, gap = gap)
        fun button(row: StripRow, glyph: Glyph, description: String, gaps: Int = 1, onClick: () -> Unit) =
            StripButton(this, glyph, description).also {
                it.showsPress = false
                it.setOnClickListener { onClick() }
                row.addSquare(it, gaps)
            }
        button(left, Glyph.BACK, "Back to the list", gaps = 0) { leave() }
        toolButtons = mapOf(
            Tool.PEN to button(island, Glyph.PEN, "Pen") { choose(Tool.PEN) },
            Tool.HIGHLIGHTER to button(island, Glyph.HIGHLIGHTER, "Highlighter") { choose(Tool.HIGHLIGHTER) },
            Tool.ERASER to button(island, Glyph.ERASER, "Eraser") { choose(Tool.ERASER) },
            Tool.LASSO to button(island, Glyph.LASSO, "Lasso select") { choose(Tool.LASSO) },
            Tool.SNIP to button(island, Glyph.SNIP, "Snip", gaps = 0) { choose(Tool.SNIP) },
        )
        island.addDivider(height = side * 2 / 5, gap = gap)
        undoButton = button(island, Glyph.UNDO, "Undo") { undoOrRedo(undo = true) }.apply { isEnabled = false }
        redoButton = button(island, Glyph.REDO, "Redo") { undoOrRedo(undo = false) }.apply { isEnabled = false }
        // Always on the island, greyed out until something is copied, so the tools never move (UI review, decision 3).
        pasteButton = button(island, Glyph.PASTE, "Paste") { togglePaste() }.apply { isEnabled = false }
        menuButton = button(right, Glyph.MENU, "Page menu", gaps = 2) { openPageMenu() }
        previousButton = button(right, Glyph.PREVIOUS, "Previous page") { turn(forward = false) }
        right.addView(indicator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = gap })
        nextButton = button(right, Glyph.NEXT, "Next page", gaps = 0) { turn(forward = true) }
        // The arrow's tip sits closer to its tile's edge than the chevron's does (0.28 of the
        // tile against 0.40), so ← gets that much more room and the two marks sit evenly in from
        // the screen's edges.
        left.setPadding(gap + (side * 0.12f).toInt(), 0, 0, 0)
        right.setPadding(0, 0, gap, 0)
        // The open file's name, cut from the front when long so its end ("workbook") shows. Set
        // once here (and when writing moves to a copy): a strip redraw flashes while fast-ink is on.
        fileName = TextView(this).apply {
            text = File(path).nameWithoutExtension
            textSize = 16f
            setTextColor(LibraryLook.GRAPHITE)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(gap, 0, 0, 0)
        }
        val strip = CentredStrip(this, gap = gap, start = left, middle = island, end = right, middleHeight = side, title = fileName).apply {
            setBackgroundColor(Color.WHITE)
        }
        stripView = strip
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, stripHeight))
            // One hairline between the strip and the page.
            addView(View(this@WritingActivity).apply { setBackgroundColor(RULE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2))
            addView(pages, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // The status bar is hidden (hideStatusBar), but a swipe can bring it back for a
            // moment, and other bars may stay: keep the strip clear of whatever is left.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        hideStatusBar()

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
                    // Here, once per picture: on screen only. The PDF and snips keep their true colours.
                    // A picture made while either setting changes is dropped when it comes
                    // (dropAllRenderings), so reading the two one after the other is enough.
                    PageLook.applyTo(bitmap, contrast, darkTextAsBlack)
                    runOnUiThread { pages.pageRendered(page, width, bitmap) }
                } catch (e: Exception) {
                    Log.w(TAG, "render failed page=$page", e)
                    runOnUiThread { pages.renderFailed(page, width) }
                }
            }
        }
        pages.requestRegionRender = { page, part, pxPerPt, token ->
            // Read now, on the UI thread: every stroke counted reached the PDF before this request.
            val version = pages.inkVersion(page)
            session.runOnDocument { pdf ->
                try {
                    val started = SystemClock.uptimeMillis()
                    // Ink solid, like the page's own picture under it; snips keep theirs smoothed.
                    val bitmap = (pdf as PdfFile).renderRegion(page, part, pxPerPt, smoothInk = false)
                    // Like the page's picture under it (requestRender), or the patch would stand out.
                    PageLook.applyTo(bitmap, contrast, darkTextAsBlack)
                    Log.i(TAG, "region rendered page=$page ${bitmap.width}x${bitmap.height} ms=${SystemClock.uptimeMillis() - started}")
                    runOnUiThread { pages.regionRendered(page, part, token, version, bitmap) }
                } catch (e: Exception) {
                    // The scaled-up page stays on show; the next zoom or move asks again.
                    Log.w(TAG, "region render failed page=$page", e)
                    runOnUiThread { pages.regionFailed(token) }
                }
            }
        }
        pages.onViewportChanged = {
            // A pinch comes here on every finger movement, and the line width and the writing
            // area are each a call into Onyx's SDK. Fast-ink is paused while fingers are down,
            // so Onyx only needs them once the fingers lift: both are told then, once.
            val fingers = pages.fingersDown
            if (fingers) penSettingsWaiting = true
            pageMaybeTurned()
            updateIndicator()
            if (!fingers) updatePenWidth()
            publishPenMapping()
            if (!fingers && documentShown) pen.setLimit(penLimit())
        }
        layOutPages(startPage = lastPages.get(path)) {
            pages.post {
                documentShown = true
                publishPenMapping()
                // A slow document can finish opening after the screen was left. The pen must not
                // come on for a screen in the background; onResume turns it on if the screen comes back.
                if (resumed) penOn()
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
        pages.onHoverEraseTrail = { points -> onHoverEraseTrail(points) }
        pages.onStylusDown = { button ->
            shownPageUntouched = false
            hoverButton = button
            publishPenChoice()
            strokeChoice = penChoice
        }
        // A palm that lands while the pen is writing must not pause fast-ink (that cuts the
        // stroke) or move the page. Fingers work again as soon as the pen lifts.
        pages.fingersAllowed = { !pen.isWriting && !screenPenDown }
        pages.onFingerGestureStart = {
            Log.i(TAG, "fingers down: pause fast-ink")
            fingerGesture = true
            shownPageUntouched = false
            handler.removeCallbacks(resumeFastInk) // a show's resume mustn't land mid-gesture
            // Nor the last gesture's refresh: this one's end brings its own.
            handler.removeCallbacks(heldCleanRefresh)
            cleanRefreshHeld = false
            pausePenAndRedraw()
        }
        pages.onFingerGestureEnd = { moved ->
            fingerGesture = false
            // Moving a zoomed page with one finger changes where the page is without telling
            // onViewportChanged (that would call Onyx on every finger movement). Fast-ink is
            // off until here anyway, so telling the pen now is in time for the next stroke.
            publishPenMapping()
            // What onViewportChanged left out during the gesture. Still before fast-ink comes back.
            if (penSettingsWaiting) {
                penSettingsWaiting = false
                updatePenWidth()
                if (documentShown) pen.setLimit(penLimit())
            }
            pages.viewport?.let { Log.i(TAG, "fingers up: pen mapping page=${it.page} zoom=${it.zoom}") }
            if (moved) {
                // Wipe fast-ink's leftover copy of the strokes, then hand the pen back.
                Log.i(TAG, "fingers up after moving: clean refresh, then resume fast-ink")
                pages.invalidate()
                // Fast-ink stays off until the refresh is done, up to PATCH_WAIT_MS +
                // CLEAN_REFRESH_MS: as for a show, the screen takes the pen meanwhile, so a
                // stroke begun then is traced and kept, not lost (resumeFastInk waits for its lift).
                pages.penHandler = screenPen
                screenHasPen = true
                if (pages.sharpPatchComing) {
                    Log.i(TAG, "clean refresh held for the sharp patch")
                    cleanRefreshHeld = true
                    handler.postDelayed(heldCleanRefresh, PATCH_WAIT_MS)
                } else {
                    handler.post(heldCleanRefresh) // on the handler, so a new gesture or leaving cancels it
                }
            } else {
                Log.i(TAG, "fingers up: resume fast-ink")
                resumeFastInk.run()
            }
        }
        pages.quietTime = { pen.drawsInk && !pen.isWriting }
        pages.mayReadyAhead = { !trimMargins || (it in measuredOnce && it !in measuring) }
        // Its picture then reaches the panel the usual way (onPageShown, below).
        pages.onRefitReady = { refitShownPage() }
        pages.onPageShown = { page, version ->
            if (page == pages.viewport?.page) {
                if (version >= previewShownFrom) layer.pageRedrawn()
                if (cleanRefreshHeld) {
                    // The sharp patch after a zoom: the held refresh shows it, in the same
                    // update. A page picture coming first is drawn under the patch still on its
                    // way, and waits with it: showing it now would make the patch a second update.
                    if (!pages.sharpPatchComing) {
                        handler.removeCallbacks(heldCleanRefresh)
                        heldCleanRefresh.run()
                    }
                } else {
                    showWithoutFastInk()
                }
            }
        }
        choose(Tool.PEN)
    }

    /**
     * Reads every page's size and placement on the worker, then lays the pages out at
     * [startPage], or the page on show: at the start, and again once a page has turned. A
     * rotation replayed at open can come before the first layout, so then it's the last page open.
     *
     * [turned]: only these pages may have changed (a rotation), so only they are read again
     * and the rest keep the size and placement they were laid out with. In a 200-page file,
     * turning one page then loads one page, not all of them.
     */
    private fun layOutPages(startPage: Int? = null, turned: Set<Int>? = null, then: () -> Unit) {
        // Nothing laid out yet (that rotation replayed at open): there's nothing to keep, read them all.
        val onlyTurned = turned != null && pdfToDisplay.isNotEmpty()
        // At open with Trim margins, the first page's printed area is measured with the layout,
        // so the page is fitted to it from the first draw instead of refitting a moment later.
        val firstPage = if (trimMargins && pdfToDisplay.isEmpty()) startPage ?: lastPages.get(path) else null
        val firstInk = firstPage?.let { pages.inkVersion(it) } ?: 0
        session.runOnDocument { pdf ->
            val file = pdf as PdfFile
            val count = file.pageCount
            val toRead = if (onlyTurned) turned.orEmpty().filter { it in 0 until count } else (0 until count).toList()
            val read = toRead.associateWith { file.layout(it) }
            val first = firstPage?.coerceIn(0, count - 1)
            val firstArea = first?.let { p -> read[p]?.first?.let { s -> printedAreaOf(file, p, SizePt(s.width, s.height)) } }
            runOnUiThread {
                if (onlyTurned && (pageSizes.size != count || pdfToDisplay.size != count)) {
                    // What was kept doesn't match the file. It shouldn't happen (a document's page count never changes), but then read everything.
                    layOutPages(startPage, turned = null, then = then)
                    return@runOnUiThread
                }
                // The pages just read, and for the rest what's laid out now. "Now", not when
                // this was asked for: a second rotation's answer builds on the first one's.
                val sizes = List(count) { read[it]?.first?.let { s -> SizePt(s.width, s.height) } ?: pageSizes[it] }
                val transforms = List(count) { read[it]?.second ?: pdfToDisplay[it] }
                // A page that turned has renderings, and a printed area, the wrong way round.
                // One being measured is dropped when it comes (printedAreaFound).
                pdfToDisplay.indices.filter { it < transforms.size && transforms[it] != pdfToDisplay[it] }.forEach {
                    pages.pageRotated(it)
                    printedAreas.remove(it)
                    measuredOnce.remove(it)
                    measuring.remove(it)
                }
                if (first != null && firstArea != null) {
                    printedAreas[first] = PrintedArea(firstArea, firstInk)
                    measuredOnce += first
                }
                pageCount = sizes.size
                pageSizes = sizes
                pdfToDisplay = transforms
                displayToPdf = transforms.map { it.inverse() }
                pages.setPages(sizes, transforms, startPage ?: pages.viewport?.page ?: lastPages.get(path), fitBoxes())
                // The page on show is laid out anew. (At open the view may have no size yet; then
                // setPages waits for it, and pageMaybeTurned does this.)
                pages.viewport?.let { pageArrived(it.page) }
                then()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Checked again when it runs: the screen may have been left in between.
        if (documentShown) pages.post { if (resumed) penOn() }
        if (showPending) retryShowWhenIdle()
    }

    /**
     * Where the pen may write: the page itself, as far as it's on screen. Ink written beside
     * the page would be saved outside it and vanish at the next redraw. Rounded inwards, so
     * not even a pixel's width lies off the page. Cut to the view (pageBoundsPx is), so a page
     * fitted to its printed area, bigger than the view, still gives Onyx a rect on the screen.
     */
    private fun penLimit(): Rect {
        val b = pages.viewport?.pageBoundsPx() ?: return Rect(0, 0, pages.width, pages.height)
        return Rect(ceil(b[0]).toInt(), ceil(b[1]).toInt(), floor(b[2]).toInt(), floor(b[3]).toInt())
    }

    /** The pen is limited to the page ([penLimit]). Fast-ink stays off if a tool needs it off. */
    private fun penOn() {
        pen.on(penLimit())
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

    /**
     * Trim margins: the box to fit each page to, the whole page for those not measured yet; or
     * null, whole pages, with it off. The boxes are in display points, as the pen's mapping is.
     */
    private fun fitBoxes(): List<Box>? {
        if (!trimMargins) return null
        return List(pageSizes.size) { printedAreas[it]?.box ?: Box(0f, 0f, pageSizes[it].w, pageSizes[it].h) }
    }

    /**
     * Lays the pages out again with the boxes there are now, on the page on show at normal
     * size. Like every layout it tells the pen the new mapping, limit and line width
     * (setPages calls onViewportChanged).
     */
    private fun fitPagesAgain() {
        val v = pages.viewport ?: return
        pages.setPages(pageSizes, pdfToDisplay, v.page, fitBoxes())
    }

    /** UI thread. [page] is on show anew: turned to, or laid out again. Nothing has touched it yet. */
    private fun pageArrived(page: Int) {
        shownPageUntouched = true
        measureAround(page)
    }

    /**
     * Measures the printed area of [page] and its neighbours, where there's none yet or the
     * page's ink has changed since. A page whose ink changes while it's on show is measured
     * again only once it's left (as a neighbour) or arrived at again, so it never jumps under the
     * pen. UI thread.
     */
    private fun measureAround(page: Int) {
        if (!trimMargins) return
        // The page on show first: the worker answers in order, and it's the one in view.
        for (p in listOf(page, page + 1, page - 1)) if (p in pageSizes.indices) measure(p)
    }

    private fun measure(page: Int) {
        val inkVersion = pages.inkVersion(page)
        if (printedAreas[page]?.inkVersion == inkVersion || page in measuring) return
        measuring += page
        val size = pageSizes[page]
        val turnedAs = pdfToDisplay[page]
        // Every stroke counted in inkVersion reached the PDF before this, as for a page picture.
        session.runOnDocument { pdf ->
            val box = printedAreaOf(pdf as PdfFile, page, size)
            runOnUiThread { printedAreaFound(page, turnedAs, inkVersion, box) }
        }
    }

    /**
     * On the worker: [page]'s box to fit (ContentBounds), [size] as shown, from one small
     * picture of it. Null if it couldn't be drawn.
     */
    private fun printedAreaOf(file: PdfFile, page: Int, size: SizePt): Box? = try {
        val started = SystemClock.uptimeMillis()
        // Smoothed, unlike the page pictures: at this size a pen stroke is under a pixel
        // wide, and drawn solid it could vanish, where smoothed it still leaves a grey.
        // No contrast step either: this picture is never shown.
        val bitmap = file.render(page, MEASURE_WIDTH_PX, smoothInk = true)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        bitmap.recycle()
        ContentBounds.trim(size, ContentBounds.of(pixels, w, h), w / size.w).also {
            Log.i(TAG, "printed area page=$page box=$it ms=${SystemClock.uptimeMillis() - started}")
        }
    } catch (e: Exception) {
        Log.w(TAG, "measuring the printed area failed page=$page", e)
        null
    }

    /**
     * UI thread. A page's printed area arrived, measured with the page turned as [turnedAs] and
     * its ink at [inkVersion]; null if it couldn't be measured (it's tried again next time). The page
     * on show is refitted to it only if nothing has touched it since it was arrived at; any
     * other page takes it at the next page turn.
     */
    private fun printedAreaFound(page: Int, turnedAs: Affine, inkVersion: Int, box: Box?) {
        // The page turned since: layOutPages dropped this one and has asked again.
        if (page !in pdfToDisplay.indices || pdfToDisplay[page] != turnedAs) return
        measuring -= page
        measuredOnce += page
        if (box == null) return pages.readyAhead() // not measurable: its picture is made ahead at the whole page's width
        // The box in use until now: the whole page if it hadn't been measured. A page that comes
        // out whole (blank, full-bleed) is then no change, and costs no refit or panel update.
        val had = printedAreas[page]?.box ?: Box(0f, 0f, pageSizes[page].w, pageSizes[page].h)
        printedAreas[page] = PrintedArea(box, inkVersion)
        // A neighbour's picture waited for this measure (PageView.mayReadyAhead). With the box
        // unchanged it's asked for here; a changed box is handed over below, which asks too.
        if (!trimMargins || box == had) return pages.readyAhead()
        val v = pages.viewport ?: return
        val boxes = fitBoxes() ?: return
        if (page == v.page && mayRefitShownPage()) {
            // Not yet: the layout, and the pen's mapping with it, change only once the page's
            // picture for the new box is here (pages.onRefitReady), so what the panel shows and
            // where the pen writes change together. If that picture is kept already (from an
            // earlier visit with this box), the page refits and is shown now. With a menu or
            // dialog open fast-ink is paused anyway.
            if (pages.refitWhenDrawn(boxes) && refitShownPage() && !dialogOpen) showWithoutFastInk()
        } else {
            pages.refitLater(boxes)
        }
    }

    /** Only a page nothing has touched since it was arrived at, with the pen up, may be refitted: it must never move under the pen. */
    private fun mayRefitShownPage() = trimMargins && shownPageUntouched && !pen.isWriting && !screenPenDown

    /**
     * UI thread. Fits the page on show to its new box, if it still may be: returns false, and
     * changes nothing, once the owner has started on the page (it takes the box when next
     * arrived at, PageView.refitLater).
     */
    private fun refitShownPage(): Boolean {
        if (!mayRefitShownPage()) return false
        Log.i(TAG, "refit page=${pages.viewport?.page} to its printed area")
        fitPagesAgain()
        return true
    }

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
     * whenever it redraws ([write]). Redrawing here used to pause fast-ink and cut off the next
     * stroke (owner's first writing session).
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
     *
     * The page view only draws itself again for it when the panel is showing the view, that is
     * when Onyx isn't drawing the ink (asked on the UI thread, where fast-ink is paused and
     * resumed). With fast-ink on, that draw would show nothing new on the panel and would take
     * longer with every stroke on the page. So everything that turns fast-ink off must redraw
     * the page view: [pausePenAndRedraw], [toolStateChanged], [onPause].
     */
    private fun write(page: Int, screenToPdf: Affine, tool: Tool, points: List<FloatArray>) {
        val kind = if (tool == Tool.HIGHLIGHTER) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
        val (_, stroke) = PenPoints.toStroke(kind, points) { x, y -> page to screenToPdf.map(Pt(x, y)) } ?: return
        session.addStroke(page, stroke)
        runOnUiThread { pages.addStroke(page, stroke, redraw = !pen.drawsInk) }
    }

    /** Button 2's trail, from Onyx's erase callbacks, on Onyx's thread. */
    private fun onEraseTrail(points: List<FloatArray>) {
        val (page, screenToPdf) = strokeMapping ?: penMapping ?: return
        if (!onyxOwnsStroke) return // fast-ink was paused and the screen had the pen; it traced this itself
        Log.i(TAG, "stroke page=$page tool=${Tool.ERASER} button=${PenButton.ERASER_END} points=${points.size} (erase callback)")
        erase(page, points.map { screenToPdf.map(Pt(it[0], it[1])) })
    }

    /**
     * UI thread. Button 2 pressed with the pen already close, then dragged: the pen never
     * reported a touch, so neither Onyx nor the screen saw a stroke ([HoverErase]). Nothing
     * was drawn, so a miss needs no wipe.
     */
    private fun onHoverEraseTrail(points: List<FloatArray>) {
        val (page, screenToPdf) = penMapping ?: return
        Log.i(TAG, "stroke page=$page tool=${Tool.ERASER} button=${PenButton.ERASER_END} points=${points.size} (button pressed while hovering)")
        erase(page, points.map { screenToPdf.map(Pt(it[0], it[1])) }, wipeIfMissed = false)
    }

    /**
     * Removes what the [trail] (PDF space) touched once the pen has lifted (plan decision 5).
     * The page is then redrawn without it, which also wipes the grey trail. If nothing was
     * hit, no change comes, so the trail is wiped straight away.
     */
    private fun erase(page: Int, trail: List<Pt>, wipeIfMissed: Boolean = true) {
        val started = SystemClock.uptimeMillis()
        session.erase(page, trail) { hits ->
            Log.i(TAG, "erase page=$page hits=$hits ms=${SystemClock.uptimeMillis() - started}")
            // Posted before the PageChanged that follows a hit, so it's marked in time.
            runOnUiThread { if (hits > 0) expectRedraw(page) else if (wipeIfMissed) showWithoutFastInk() }
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
        pageArrived(page)
    }

    /** UI thread. Lets go of the selection and cancels an armed paste, if there's either. */
    private fun letGoOfAll() {
        if (!toolState.hasSelection && !toolState.pasteArmed) return
        letGo()
        toolState.pasteArmed = false
        toolStateChanged()
    }

    /**
     * The Paste button (black while [ToolState.showPaste], grey before): arms a paste for the next pen
     * tap on the page, or cancels one already armed.
     */
    private fun togglePaste() {
        if (toolState.clipboard == null) return
        toolState.pasteArmed = !toolState.pasteArmed
        Log.i(TAG, "paste armed=${toolState.pasteArmed}")
        toolStateChanged()
    }

    /**
     * A finger long-press on the page while something is copied: a small Paste button at that
     * spot, and tapping it pastes there (owner, 2026-11-10: paste with a finger, confirmed so a
     * stray press can't). Fast-ink is off while it shows, as for the page menu.
     */
    private fun offerPaste(x: Float, y: Float) {
        if (toolState.clipboard == null || pen.isWriting || dialogOpen) return
        val frame = pages.frame() ?: return
        val at = frame.displayToScreen.inverse().map(Pt(x, y))
        if (at.x < 0 || at.y < 0 || at.x > frame.size.w || at.y > frame.size.h) return // beside the page
        // Straight after Copy the selection is still there: let go of it, as a tap outside would.
        if (toolState.hasSelection) {
            letGo()
            toolStateChanged()
        }
        // As tall as a strip button, with round ends like the island (UI review, finding 8).
        val button = TextView(this).apply {
            text = "Paste"
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, 0, pad, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((resources.displayMetrics.density * 2).toInt(), Color.BLACK)
                cornerRadius = stripSide / 2f
            }
        }
        val popup = android.widget.PopupWindow(button, LinearLayout.LayoutParams.WRAP_CONTENT, stripSide, true)
        button.setOnClickListener {
            popup.dismiss()
            Log.i(TAG, "paste by long-press")
            selectionListener.pasteAt(at)
        }
        dialogOpen = true
        pausePenAndRedraw()
        popup.setOnDismissListener {
            dialogOpen = false
            handler.removeCallbacks(resumeFastInk)
            handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
        }
        val origin = IntArray(2).also { pages.getLocationOnScreen(it) }
        // Just above the finger, so the finger doesn't hide it.
        button.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAtLocation(pages, Gravity.NO_GRAVITY,
            (origin[0] + x - button.measuredWidth / 2f).toInt(), (origin[1] + y - stripSide * 1.5f).toInt())
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
            showStripChange(updateStrip()) // Paste turns black
            showCopied()
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
                    // Paste turns black. The snip may finish after fast-ink is back, so it's put on the panel.
                    showStripChange(updateStrip())
                    showCopied()
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
        val stripChanged = updateStrip()
        layer.snipping = toolState.chosen == Tool.SNIP
        layer.pasteArmed = toolState.pasteArmed
        if (toolState.fastInkOn()) {
            pauseWhenLifted = false
            showStripChange(stripChanged)
            // Fast-ink on before and after (one drawing tool for another): the page has nothing
            // new to show, and redrawing it would make the panel refresh the whole screen.
            if (pen.drawsInk) return
            handler.removeCallbacks(resumeFastInk)
            pages.invalidate()
            // Once the screen has drawn the page without the box or rectangle.
            handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
            return
        }
        handler.removeCallbacks(resumeFastInk)
        pages.invalidate()
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
        // Padded as the library's name fields are, so the number isn't against the edge.
        val pad = LibraryLook.dp(this, 20)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(field) })
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
        pausePenAndRedraw()
        dialog.setOnDismissListener {
            dialogOpen = false
            // After the usual pause, not at once: the screen must first draw the page without
            // the dialog, or fast-ink would freeze bits of it (or the old page) on the panel.
            handler.removeCallbacks(resumeFastInk)
            handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
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
        // Two groups with a line between: rotating changes the PDF, the rest only the screen (UI review, decision 4).
        menu.menu.add(ROTATE_GROUP, Menu.NONE, Menu.NONE, "Rotate this page").setOnMenuItemClickListener { rotate(all = false); true }
        menu.menu.add(ROTATE_GROUP, Menu.NONE, Menu.NONE, "Rotate all pages").setOnMenuItemClickListener { rotate(all = true); true }
        // A submenu, not a second menu: it stays one menu to Android, so the dismiss below
        // comes once, when the last of it closes, and fast-ink stays paused in between.
        val looks = menu.menu.addSubMenu(LOOK_GROUP, Menu.NONE, Menu.NONE, "Contrast: ${contrast.label()}")
        Contrast.values().forEach { c ->
            looks.add(c.label()).setChecked(c == contrast).setOnMenuItemClickListener { chooseContrast(c); true }
        }
        looks.setGroupCheckable(0, true, true) // the one in use is ticked
        menu.menu.add(LOOK_GROUP, Menu.NONE, Menu.NONE, "Dark text as black").setCheckable(true).setChecked(darkTextAsBlack)
            .setOnMenuItemClickListener { chooseDarkTextAsBlack(!darkTextAsBlack); true }
        menu.menu.add(LOOK_GROUP, Menu.NONE, Menu.NONE, "Trim margins").setCheckable(true).setChecked(trimMargins)
            .setOnMenuItemClickListener { chooseTrimMargins(!trimMargins); true }
        menu.menu.setGroupDividerEnabled(true)
        dialogOpen = true
        pausePenAndRedraw()
        menu.setOnDismissListener {
            dialogOpen = false
            // After the usual pause, as for "Go to page".
            handler.removeCallbacks(resumeFastInk)
            handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
        }
        menu.show()
    }

    /** The page pictures are made again with [chosen], which is kept for next time. */
    private fun chooseContrast(chosen: Contrast) {
        if (chosen == contrast) return
        Log.i(TAG, "contrast $chosen")
        readingPrefs.contrast = chosen
        contrast = chosen // before asking again, so every picture asked for from now on has it
        pages.dropAllRenderings()
    }

    /** The page pictures are made again with dark text as black ([on]) or not, which is kept for next time. */
    private fun chooseDarkTextAsBlack(on: Boolean) {
        Log.i(TAG, "dark text as black $on")
        readingPrefs.darkTextAsBlack = on
        darkTextAsBlack = on // before asking again, as for contrast
        pages.dropAllRenderings()
    }

    private fun Contrast.label() = name.lowercase().replaceFirstChar { it.uppercase() }

    /**
     * Fits pages to their printed area ([on]) or to the whole sheet again, and keeps the
     * choice for next time. Screen only: the PDF is the same either way.
     */
    private fun chooseTrimMargins(on: Boolean) {
        Log.i(TAG, "trim margins $on")
        readingPrefs.trimMargins = on
        trimMargins = on
        fitPagesAgain()
        pages.viewport?.let { pageArrived(it.page) }
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
        // As pausePenAndRedraw, with the panel update chosen for a new look of the page.
        pen.pause()
        pen.showPage(pages)
        // While fast-ink is paused Onyx doesn't follow the pen. With the pen or highlighter
        // chosen nothing else did either, so a stroke begun in this short pause (erase a word,
        // carry straight on writing) was lost or cut. The screen takes the pen for the pause:
        // it traces such a stroke and records it as ink, and resumeFastInk waits for its lift.
        pages.penHandler = screenPen
        screenHasPen = true
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
     * last picture. That picture is from before the latest strokes: the page view doesn't
     * draw itself for a new stroke while fast-ink is on ([write]), and the screen ignores normal
     * drawing then anyway. So draw it again right away, with the strokes. Everything that
     * pauses fast-ink comes through here or redraws the page view itself.
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

    /**
     * The chosen tool picked out, and Paste black once there's something to paste (grey before),
     * picked out while armed. Holding a pen button doesn't change it: showing that would flash the screen
     * at every press (owner's choice, 2026-11-10). Returns whether anything changed.
     */
    private fun updateStrip(): Boolean {
        val shown = toolState.chosen
        val changed = toolButtons.any { (tool, button) -> button.isSelected != (tool == shown) } ||
            pasteButton.isEnabled != toolState.showPaste || pasteButton.isSelected != toolState.pasteArmed
        toolButtons.forEach { (tool, button) -> button.isSelected = tool == shown }
        pasteButton.isEnabled = toolState.showPaste
        pasteButton.isSelected = toolState.pasteArmed
        return changed
    }

    /**
     * The screen ignores normal drawing while fast-ink is on, so a strip that [changed] then
     * is put on the panel by itself, with one quick flash (owner-tested). No pause: a stroke
     * begun in a pause gets no live line. With fast-ink off it's on screen already.
     */
    private fun showStripChange(changed: Boolean) {
        if (changed && fastInkWanted()) {
            Log.i(TAG, "strip shown alone")
            pen.showAlone(stripView)
        }
    }

    /** Full screen while writing: the status bar comes back for a moment with a swipe down from the top. */
    private fun hideStatusBar() {
        window.insetsController?.let {
            it.hide(android.view.WindowInsets.Type.statusBars())
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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

    /**
     * The volume buttons turn the page (owner, 2026-11-10): down for the next page, up for the
     * previous one; Page Down and Up too, as a clip-on page turner sends. Held down, a key
     * repeats; only the first press turns. Not while the pen is writing: the stroke would land
     * on the new page.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val forward = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> true
            android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> false
            else -> return super.onKeyDown(keyCode, event)
        }
        if (event.repeatCount == 0 && documentShown && !pen.isWriting && !screenPenDown) turn(forward)
        return true // taken, so the system's volume panel doesn't open over the page
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        keyCode in PAGE_KEYS || super.onKeyUp(keyCode, event)

    @Deprecated("Logged for the leave timings; then the usual back.")
    override fun onBackPressed() {
        Log.i(TAG, "leave: back")
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        val start = SystemClock.uptimeMillis()
        // Nothing may turn fast-ink back on in the background; onResume starts it afresh.
        handler.removeCallbacks(retryShow)
        handler.removeCallbacks(resumeFastInk)
        handler.removeCallbacks(heldCleanRefresh) // it would resume fast-ink after its refresh
        cleanRefreshHeld = false
        pen.off()
        // The page view's picture may lack the latest strokes ([write]). The screen can stay
        // in view after this (the tablet going to sleep, another window on top), so draw them.
        pages.invalidate()
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
        if (::pages.isInitialized) pages.release()
        // onPause has done this already; once more in case anything turned the pen on since.
        if (::pen.isInitialized) pen.off()
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
            is SessionEvent.PageChanged -> if (event.rotated) layOutPages(turned = event.pages) { pagesChanged(event.pages) } else pagesChanged(event.pages)
            is SessionEvent.HistoryChanged -> {
                undoButton.isEnabled = event.canUndo
                redoButton.isEnabled = event.canRedo
            }
            is SessionEvent.EarlierWritingKept -> toast("Unsaved writing from before this file changed was kept safe.")
            is SessionEvent.SavedToCopy -> {
                // The session writes into the copy from here on, so the copy is what "Continue"
                // and the remembered page must follow, not the damaged original.
                path = event.copy.path
                fileName.text = event.copy.nameWithoutExtension
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
        pausePenAndRedraw()
        dialog.setOnDismissListener {
            dialogOpen = false
            handler.removeCallbacks(resumeFastInk)
            handler.postDelayed(resumeFastInk, SHOW_PAUSE_MS)
        }
        dialog.show()
    }

    /** With the app's context, not the screen's: a message can come after the screen has closed (see [show]). */
    private fun toast(text: String, length: Int = Toast.LENGTH_LONG) = Toast.makeText(applicationContext, text, length).show()

    /** After a copy or snip: how to paste the first few times, then just "Copied." (UI review, decision 6). */
    private fun showCopied() {
        val shown = readingPrefs.copyHintsShown
        toast(copyMessage(shown), if (shown < COPY_HINTS) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        if (shown < COPY_HINTS) readingPrefs.copyHintsShown = shown + 1
    }

    companion object {
        const val EXTRA_PATH = "path"

        /** After Copy or a snip, which otherwise only show as the Paste button appearing (owner, 2026-11-10). */
        /** The ⋯ menu's groups: what changes the PDF, then what changes only the screen. */
        private const val ROTATE_GROUP = 1
        private const val LOOK_GROUP = 2
        private val PAGE_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_PAGE_UP,
        )
        private const val NO_PAGE = -1
        const val SHOW_PAUSE_MS = 150L

        /** How long the pen must rest before a show skipped while writing is tried again. */
        const val SHOW_RETRY_IDLE_MS = 500L

        /** How wide a page is drawn to find its printed area (Trim margins): small, so it's quick. */
        private const val MEASURE_WIDTH_PX = 200

        /** Snips (plan decision 9): 2 pixels per point, at most 2000 pixels on the long side. */
        const val SNIP_PX_PER_PT = 2f
        const val SNIP_MAX_PX = 2000f

        /** The hairline under the strip. */
        private val RULE = Color.rgb(200, 200, 200)

        /** The refresh after a zoom (REGAL) takes about this long; the pen comes back after it. The owner found the wait fine (2026-11-10). */
        const val CLEAN_REFRESH_MS = 450L

        /** How long the clean refresh after a zoom waits for the sharp patch (see heldCleanRefresh). */
        const val PATCH_WAIT_MS = 300L
        private const val TAG = "Notes"
    }
}
