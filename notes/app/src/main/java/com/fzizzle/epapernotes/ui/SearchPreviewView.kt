package com.fzizzle.epapernotes.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.fzizzle.epapernotes.library.Hit
import com.fzizzle.epapernotes.library.NameMatch
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.library.SearchPreview
import com.fzizzle.epapernotes.library.TileMarks
import java.io.File

/**
 * The matches under the search box ([SearchPreview]): a box [SearchPreview.VISIBLE_ROWS] rows
 * tall, as the library's list rows, that scrolls through up to [SearchPreview.LIMIT] of them,
 * and a line under it. Asked for [SearchPreview.PAUSE_MS] after the last key, not at every key.
 * Its height is fixed from the start, so the dialog never changes size, and a new answer is one
 * small update of this area alone.
 */
@SuppressLint("ViewConstructor")
internal class SearchPreviewView(
    context: Context,
    private val field: EditText,
    private val root: File,
    /** The index's preview: one answer, on the main thread. */
    private val ask: (query: String, onAnswer: (NameIndex.SearchAnswer) -> Unit) -> Unit,
    /** A row tapped: its PDF, with the query it was found by. */
    private val onOpen: (pdf: File, query: String) -> Unit,
) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())

    /** Counts the previews asked for: only the newest one's answer is shown. */
    private var asked = 0
    private var shownHits: List<Hit> = emptyList()
    private var shownQuery = ""

    private val rows = object : BaseAdapter() {
        override fun getCount() = shownHits.size
        override fun getItem(position: Int) = shownHits[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? RowView ?: RowView(context)
            val hit = shownHits[position]
            // The index holds only names ending ".pdf" (any case): the name shown is without it.
            val name = hit.fileName.dropLast(PDF_SUFFIX_LENGTH)
            row.show(PdfEntry(fileOf(hit), name, 0, 0), name, NameMatch.folderShown(hit.folder), TileMarks.NONE)
            return row
        }
    }

    private val list = ListView(context).apply {
        adapter = rows
        divider = null // the rows draw their own hairline
        selector = ColorDrawable(Color.TRANSPARENT) // rows show their own pressed state, without a fade
        overScrollMode = View.OVER_SCROLL_NEVER // no glow at the ends: it's an animation
        isScrollbarFadingEnabled = false // nor a fading scrollbar
        isVerticalScrollBarEnabled = true
        setOnItemClickListener { _, _, position, _ -> shownHits.getOrNull(position)?.let { onOpen(fileOf(it), shownQuery) } }
    }

    private val line = TextView(context).apply {
        textSize = 15f
        setTextColor(LibraryLook.GRAPHITE)
        // One line, always: a new text never changes its height.
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        setPadding(LibraryLook.dp(context, 12), LibraryLook.dp(context, 6), 0, LibraryLook.dp(context, 6))
    }

    private val askNow = Runnable { refresh() }

    private val typed = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            main.removeCallbacks(askNow)
            main.postDelayed(askNow, SearchPreview.PAUSE_MS)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, LibraryLook.dp(context, 8), 0, 0)
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LibraryLook.dp(context, ROW_HEIGHT_DP) * SearchPreview.VISIBLE_ROWS))
        addView(line)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        field.addTextChangedListener(typed)
        // The last query, filled in: its matches show with the dialog.
        refresh()
    }

    override fun onDetachedFromWindow() {
        field.removeTextChangedListener(typed)
        main.removeCallbacks(askNow)
        // An answer still on its way is dropped.
        asked++
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val query = field.text.toString()
        val ask = ++asked
        if (!SearchPreview.wanted(query)) return show(query, emptyList(), "")
        ask(query) { answer ->
            if (ask != asked || !isAttachedToWindow) return@ask
            val hits = answer.matches.hits
            val text = if (answer.rootMissing) "The library folder isn't there." else SearchPreview.line(true, hits.size, answer.matches.total)
            show(query, hits, text)
        }
    }

    /** Puts [hits] and [text] on the rows and the line; one small panel update, and none when nothing changed. */
    private fun show(query: String, hits: List<Hit>, text: String) {
        shownQuery = query
        val sameRows = hits == shownHits
        val sameLine = line.text.toString() == text
        if (sameRows && sameLine) return
        if (!sameRows) {
            shownHits = hits
            rows.notifyDataSetChanged()
            // New matches start at the top.
            list.setSelection(0)
        }
        if (!sameLine) line.text = text
        PanelRefresh.line(this)
    }

    private fun fileOf(hit: Hit) = File(root, hit.folder + "/" + hit.fileName)

    private companion object {
        const val PDF_SUFFIX_LENGTH = 4
    }
}
