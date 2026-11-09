package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
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
    private lateinit var penButton: Button
    private lateinit var highlighterButton: Button
    private var tool = Tool.PEN

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        lastPages = LastPageStore(this)

        pages = PageView(this)
        indicator = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER_VERTICAL }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(Button(this@WritingActivity).apply { text = "←"; setOnClickListener { finish() } })
            penButton = Button(this@WritingActivity).apply { text = "Pen"; setOnClickListener { choose(Tool.PEN) } }
            highlighterButton = Button(this@WritingActivity).apply { text = "Highlighter"; setOnClickListener { choose(Tool.HIGHLIGHTER) } }
            addView(penButton)
            addView(highlighterButton)
            addView(android.view.View(this@WritingActivity), LinearLayout.LayoutParams(0, 1, 1f)) // pushes the page controls right
            addView(Button(this@WritingActivity).apply { text = "‹"; setOnClickListener { turn(forward = false) } })
            addView(indicator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 16; marginEnd = 16 })
            addView(Button(this@WritingActivity).apply { text = "›"; setOnClickListener { turn(forward = true) } })
        }
        val stripHeight = (resources.displayMetrics.heightPixels * 0.05f).toInt() // about 5% (spec §6.2)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, stripHeight))
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

        pen = PenInput(pages, onPenDown = { strokeMapping = penMapping }) { points -> onPenStroke(points) }
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

    /**
     * A finished pen stroke, on Onyx's thread. Into the journal straight away (spec §7.3).
     * The page isn't redrawn: Onyx's ink stays on screen, and the page view keeps its own copy
     * of the stroke for whenever it redraws. Redrawing here used to pause fast-ink and cut off
     * the next stroke (owner's first writing session).
     */
    private fun onPenStroke(points: List<FloatArray>) {
        val (page, screenToPdf) = strokeMapping ?: penMapping ?: return
        val kind = if (tool == Tool.HIGHLIGHTER) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
        val (_, stroke) = PenPoints.toStroke(kind, points) { x, y -> page to screenToPdf.map(Pt(x, y)) } ?: return
        session.addStroke(page, stroke)
        Log.i(TAG, "stroke page=$page points=${stroke.points.size} kind=$kind")
        runOnUiThread { pages.addStroke(page, stroke) }
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
            return
        }
        Log.i(TAG, "show: pause fast-ink")
        pausePenAndRedraw()
        handler.postDelayed({
            Log.i(TAG, "show: resume fast-ink")
            pen.resume()
        }, SHOW_PAUSE_MS)
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
        tool = newTool
        penButton.setTypeface(null, if (tool == Tool.PEN) Typeface.BOLD else Typeface.NORMAL)
        highlighterButton.setTypeface(null, if (tool == Tool.HIGHLIGHTER) Typeface.BOLD else Typeface.NORMAL)
        penButton.text = if (tool == Tool.PEN) "● Pen" else "Pen"
        highlighterButton.text = if (tool == Tool.HIGHLIGHTER) "● Highlighter" else "Highlighter"
        updatePenWidth()
    }

    /** The live ink should be as wide as the saved ink will be, at this zoom. */
    private fun updatePenWidth() {
        val v = pages.viewport
        pen.setTool(tool, v?.pxPerPt() ?: 1f)
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
    }

    private fun show(event: SessionEvent) {
        when (event) {
            SessionEvent.Saved -> Unit // saving is silent when it works
            is SessionEvent.SaveFailed -> toast("Couldn't save: ${event.message}. Your writing is kept safe and will be saved again.")
            SessionEvent.ChangedOnDisk -> toast("This file was changed elsewhere, so it isn't being saved into. Your writing is kept safe.")
            SessionEvent.NeedsRepairDecision -> askAboutRepair()
            is SessionEvent.Replayed -> toast("Recovered ${event.count} stroke(s) that hadn't been saved.")
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

        /** A full e-ink refresh takes about this long; the pen comes back after it. */
        const val CLEAN_REFRESH_MS = 450L
        private const val TAG = "Notes"
    }
}
