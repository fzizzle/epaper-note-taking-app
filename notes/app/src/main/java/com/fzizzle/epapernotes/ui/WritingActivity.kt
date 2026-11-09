package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * The writing screen (spec §6.2): the pages fill the screen under one thin strip.
 * Plan 2 has the back button, the pen and highlighter, and the page indicator. The other
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

    // Pages written on since they were last redrawn from the PDF.
    private val inkedPages = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val redrawInked = Runnable { redrawInkedPages() }

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
            addView(indicator, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 24 })
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
                val bitmap = (pdf as PdfFile).render(page, width)
                runOnUiThread { pages.pageRendered(page, width, bitmap) }
            }
        }
        pages.onViewportChanged = {
            updateIndicator()
            updatePenWidth()
        }
        session.runOnDocument { pdf ->
            val file = pdf as PdfFile
            val sizes = (0 until file.pageCount).map { file.displaySize(it).let { s -> SizePt(s.width, s.height) } }
            val inverses = (0 until file.pageCount).map { file.pdfToDisplay(it).inverse() }
            runOnUiThread {
                pageCount = sizes.size
                displayToPdf = inverses
                pages.setPages(sizes, lastPages.get(path))
            }
        }

        pen = PenInput(pages) { points -> onPenStroke(points) }
        pages.onFingerGestureStart = { pen.pause() }
        pages.onFingerGestureEnd = { pen.resume() }
        pages.onPageShown = { showWithoutFastInk() }
        choose(Tool.PEN)
    }

    override fun onResume() {
        super.onResume()
        // The view needs its size first: the pen is limited to the page area, below the strip.
        pages.post { pen.on(Rect(0, 0, pages.width, pages.height)) }
    }

    /** A finished pen stroke, on Onyx's thread. Into the journal straight away (spec §7.3). */
    private fun onPenStroke(points: List<FloatArray>) {
        val viewport = pages.viewport ?: return
        if (displayToPdf.isEmpty()) return
        val kind = if (tool == Tool.HIGHLIGHTER) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
        val (page, stroke) = PenPoints.toStroke(kind, points) { x, y ->
            val (p, display) = viewport.screenToPage(x, y)
            p to displayToPdf[p].map(Pt(display.x, display.y))
        } ?: return
        session.addStroke(page, stroke)
        runOnUiThread {
            inkedPages += page
            // Once the pen has rested a moment, replace Onyx's live ink with the saved version.
            handler.removeCallbacks(redrawInked)
            handler.postDelayed(redrawInked, PEN_IDLE_REDRAW_MS)
        }
    }

    private fun redrawInkedPages() {
        inkedPages.forEach { pages.refreshPage(it) }
        inkedPages.clear()
    }

    /** The screen is frozen to normal drawing while fast-ink is on, so pause it briefly to show a fresh page. */
    private fun showWithoutFastInk() {
        pen.pause()
        handler.postDelayed({ pen.resume() }, 150)
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
        pen.setTool(tool, v?.pxPerPt(v.currentPage()) ?: 1f)
    }

    override fun onPause() {
        super.onPause()
        pen.off()
        handler.removeCallbacks(redrawInked)
        redrawInkedPages()
        pages.viewport?.let { lastPages.set(path, it.topPage()) }
        // Going to the background or leaving: save now, not in a few seconds (spec §7.2).
        session.saveNow()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::session.isInitialized) session.close()
    }

    private fun updateIndicator() {
        val v = pages.viewport ?: return
        indicator.text = "${v.currentPage() + 1} / $pageCount"
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
        const val PEN_IDLE_REDRAW_MS = 1000L
    }
}
