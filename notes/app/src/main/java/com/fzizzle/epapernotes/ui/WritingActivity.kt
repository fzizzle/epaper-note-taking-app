package com.fzizzle.epapernotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.fzizzle.epapernotes.doc.DocumentSession
import com.fzizzle.epapernotes.doc.SessionEvent
import com.fzizzle.epapernotes.doc.WorkerScheduler
import com.fzizzle.epapernotes.pdf.PdfFile
import java.io.File

/**
 * The writing screen (spec §6.2): the pages fill the screen under one thin strip.
 * Plan 2 has the back button and the page indicator here. The pen and highlighter come in
 * Task 9, the other tools in plan 3.
 */
class WritingActivity : Activity() {

    private lateinit var session: DocumentSession
    private lateinit var pages: PageView
    private lateinit var indicator: TextView
    private lateinit var lastPages: LastPageStore
    private lateinit var path: String
    private var pageCount = 0

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
        pages.onViewportChanged = { updateIndicator() }
        session.runOnDocument { pdf ->
            val file = pdf as PdfFile
            val sizes = (0 until file.pageCount).map { file.displaySize(it).let { s -> SizePt(s.width, s.height) } }
            runOnUiThread {
                pageCount = sizes.size
                pages.setPages(sizes, lastPages.get(path))
            }
        }
    }

    override fun onPause() {
        super.onPause()
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
    }
}
