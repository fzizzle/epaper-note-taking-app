package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.fzizzle.epapernotes.doc.DocumentSession
import com.fzizzle.epapernotes.doc.SessionEvent
import com.fzizzle.epapernotes.doc.WorkerScheduler
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.pdf.PdfFile
import com.fzizzle.epapernotes.tools.PenButton
import com.fzizzle.epapernotes.tools.Tool
import com.fzizzle.epapernotes.tools.ToolState
import com.fzizzle.epapernotes.tools.liveLine
import java.io.File

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

    /** The button the pen last showed while hovering or landing. UI thread. */
    private var hoverButton = PenButton.NONE

    /** For each page, PDF space to display points, inverted: how a pen point finds its place in the PDF. */
    private var displayToPdf: List<Affine> = emptyList()
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
                handler.removeCallbacks(retryShow)
            },
            onStroke = { points -> onPenStroke(points) },
            onEraseTrail = { points -> onEraseTrail(points) },
            onPenUp = { if (showPending) retryShowWhenIdle() },
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
            pausePenAndRedraw()
        }
        pages.onFingerGestureEnd = { moved ->
            if (moved) {
                // Wipe fast-ink's leftover copy of the strokes, then hand the pen back.
                Log.i(TAG, "fingers up after moving: clean refresh, then resume fast-ink")
                pages.invalidate()
                pages.post {
                    pen.cleanRefresh(pages)
                    handler.postDelayed({ pen.resume() }, CLEAN_REFRESH_MS)
                }
            } else {
                Log.i(TAG, "fingers up: resume fast-ink")
                pen.resume()
            }
        }
        pages.onPageShown = { page -> if (page == pages.viewport?.page) showWithoutFastInk() }
        choose(Tool.PEN)
    }

    override fun onResume() {
        super.onResume()
        if (documentShown) pages.post { penOn() }
    }

    /** The pen is limited to the page area, below the strip. */
    private fun penOn() {
        pen.on(Rect(0, 0, pages.width, pages.height))
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
     * whenever it redraws. Redrawing here used to pause fast-ink and cut off the next stroke
     * (owner's first writing session).
     */
    private fun onPenStroke(points: List<FloatArray>) {
        val (page, screenToPdf) = strokeMapping ?: penMapping ?: return
        val (button, tool) = strokeChoice ?: penChoice
        Log.i(TAG, "stroke page=$page tool=$tool button=$button points=${points.size}")
        when (tool) {
            Tool.PEN, Tool.HIGHLIGHTER -> {
                val kind = if (tool == Tool.HIGHLIGHTER) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
                val (_, stroke) = PenPoints.toStroke(kind, points) { x, y -> page to screenToPdf.map(Pt(x, y)) } ?: return
                session.addStroke(page, stroke)
                runOnUiThread { pages.addStroke(page, stroke) }
            }
            Tool.ERASER -> erase(page, points.map { screenToPdf.map(Pt(it[0], it[1])) })
            Tool.LASSO -> {
                val loop = points.map { screenToPdf.map(Pt(it[0], it[1])) }
                session.select(page, loop) { items ->
                    // Task 8 turns this into a selection. Until then, just wipe the grey loop.
                    Log.i(TAG, "lasso page=$page selected=${items.size}")
                    runOnUiThread { showWithoutFastInk() }
                }
            }
            Tool.SNIP -> Unit // fast-ink is off while snipping (ToolState.fastInkOn); Task 8 draws it
        }
    }

    /** Button 2's trail, from Onyx's erase callbacks, on Onyx's thread. */
    private fun onEraseTrail(points: List<FloatArray>) {
        val (page, screenToPdf) = strokeMapping ?: penMapping ?: return
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
        pen.pause()
        dialog.setOnDismissListener { pen.resume() }
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
        handler.postDelayed({
            Log.i(TAG, "show: resume fast-ink")
            pen.resume()
        }, SHOW_PAUSE_MS)
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

    private fun choose(newTool: Tool) {
        toolState.chosen = newTool
        penButton.isSelected = newTool == Tool.PEN
        highlighterButton.isSelected = newTool == Tool.HIGHLIGHTER
        publishPenChoice()
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
        pen.off()
        pages.viewport?.let { lastPages.set(path, it.page) }
        // Going to the background or leaving: save now, not in a few seconds (spec §7.2).
        session.saveNow()
    }

    override fun onDestroy() {
        super.onDestroy()
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

        /** The hairline under the strip. */
        private val RULE = Color.rgb(200, 200, 200)

        /** A full e-ink refresh takes about this long; the pen comes back after it. */
        const val CLEAN_REFRESH_MS = 450L
        private const val TAG = "Notes"
    }
}
