package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.fzizzle.epapernotes.library.Breadcrumb
import com.fzizzle.epapernotes.library.Entry
import com.fzizzle.epapernotes.library.FolderEntry
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.library.TileMarks
import com.fzizzle.epapernotes.library.TileNames
import java.io.File

/** The library's look, shared by its screen, tiles and windows: black on white, light grey hairlines. */
internal object LibraryLook {
    val RULE = Color.rgb(200, 200, 200)
    val GRAPHITE = Color.rgb(77, 77, 77)
    /** A PDF tile waiting for its thumbnail (decision 8). */
    val WAITING_PAGE = Color.rgb(228, 228, 228)

    /** A plain grey while pressed, with no fade: animations only cause flashing on e-ink. */
    fun pressedFeedback() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(RULE))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        setExitFadeDuration(0)
        setEnterFadeDuration(0)
    }

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
}

/** What a folder holds, in a word or two: its PDFs, or its folders if it has none. */
internal fun folderCountText(folder: FolderEntry): String = when {
    folder.pdfCount == 1 -> "1 PDF"
    folder.pdfCount > 1 -> "${folder.pdfCount} PDFs"
    folder.folderCount == 1 -> "1 folder"
    folder.folderCount > 1 -> "${folder.folderCount} folders"
    else -> "No PDFs"
}

/**
 * The grid's tiles, reused as it scrolls. [bitmapFor] only looks in memory (ThumbnailLoader),
 * so binding a tile never touches the disk. With [thumbnails] off, PDF tiles are drawn pages.
 */
class LibraryAdapter(
    private val bitmapFor: (PdfEntry) -> Bitmap?,
    /** A tile's progress bar, writing mark and colour (TileMarks), asked each time a tile is bound: memory only. */
    private val marksFor: (Entry) -> TileMarks = { TileMarks.NONE },
    /** The right-hand words of a list row: "12 / 30", "30 pages", "4 PDFs". Memory only. */
    private val detailFor: (Entry) -> String = { "" },
) : BaseAdapter() {
    /** Rows, not tiles (the list view). */
    var listView = false
        private set

    /** The file whose tile or row is marked: the document just come back from. Null for none. */
    var markedFile: java.io.File? = null

    /**
     * The file whose long-press menu is open. Kept here, by file, and not only on the tile's
     * view: a folder refresh under the open menu hands the views out again, and the mark went
     * to whichever file got that view.
     */
    var menuFile: java.io.File? = null

    fun isMarked(file: java.io.File?) = file != null && (file == markedFile || file == menuFile)

    var entries: List<Entry> = emptyList()
        private set
    var thumbnails = true
        private set
    /** Goes up on each refresh: tiles bound before it don't keep their old pictures. */
    private var pictures = 0
    /** The PDFs' tile names: a heading for one shown in place of its small folder, and its name without the part shared with its neighbours (TileNames). */
    private var pdfLabels: Map<PdfEntry, Pair<String?, String>> = emptyMap()

    /** [dropPictures]: refresh, so tiles waiting for a new thumbnail show the grey page meanwhile. */
    fun show(entries: List<Entry>, thumbnails: Boolean, dropPictures: Boolean = false, listView: Boolean = this.listView) {
        this.entries = entries
        this.listView = listView
        pdfLabels = TileNames.grouped(entries.filterIsInstance<PdfEntry>())
        this.thumbnails = thumbnails
        if (dropPictures) pictures++
        notifyDataSetChanged()
    }

    override fun getCount() = entries.size
    override fun getItem(position: Int) = entries[position]
    override fun getItemId(position: Int) = position.toLong()

    // Tiles and rows are kept apart, so the grid never hands a tile back to be a row.
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (listView) 1 else 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val entry = entries[position]
        val (heading, label) = (entry as? PdfEntry)?.let { pdfLabels[it] } ?: (null to entry.name)
        val params = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (listView) {
            val row = convertView as? RowView ?: RowView(parent.context).apply { layoutParams = params }
            // On one line: the small folder a PDF is shown in place of, then its own short name.
            row.show(entry, if (heading != null) "$heading — $label" else label, detailFor(entry), marksFor(entry))
            row.marked = isMarked(entry.file)
            return row
        }
        val tile = convertView as? TileView ?: TileView(parent.context).apply { layoutParams = params }
        tile.show(entry, label, if (thumbnails && entry is PdfEntry) bitmapFor(entry) else null, thumbnails, pictures, marksFor(entry), heading)
        tile.marked = isMarked(entry.file)
        return tile
    }
}

/**
 * One tile, drawn with plain shapes: a square box holding the PDF's first page (or a grey page
 * while it's on its way, or a drawn page with thumbnails off), or for a folder a small folder
 * mark in a band [FOLDER_MARK] of that height; then the name on up to two lines, and for a folder
 * how many PDFs are directly inside (decision 2). Folder tiles are shorter, so a course's topics
 * fit on one screen (UI review, decision 5); a row holding both takes the PDF tiles' height.
 */
class TileView(context: Context) : View(context), LibraryItemView {
    override var entry: Entry? = null
        private set
    /** The name drawn: for a PDF, perhaps shorter than its file name (TileNames). */
    private var label = ""
    /** For a PDF shown in place of its small folder: that folder's name, drawn over [label]. */
    private var heading: String? = null
    private var bitmap: Bitmap? = null
    private var thumbnails = true
    private var pictures = 0 // the adapter's count when this tile was bound
    private var marks = TileMarks.NONE
    private val barHeight = LibraryLook.dp(context, 3).toFloat()
    private val inkMark = LibraryLook.dp(context, 12).toFloat()
    private val stripeWidth = LibraryLook.dp(context, 4).toFloat()

    private val pad = LibraryLook.dp(context, 8)
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    }
    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LibraryLook.GRAPHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    /** A grouped PDF's own name, under its folder's: the name's size, in grey. */
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LibraryLook.GRAPHITE
        textSize = namePaint.textSize
        textAlign = Paint.Align.CENTER
    }
    private val nameLine = namePaint.getFontMetricsInt(null)
    private val countLine = countPaint.getFontMetricsInt(null)
    private var nameLayout: StaticLayout? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private val page = RectF()
    private val path = Path()

    fun show(entry: Entry, label: String, bitmap: Bitmap?, thumbnails: Boolean, pictures: Int, marks: TileMarks = TileMarks.NONE, heading: String? = null) {
        if (entry == this.entry && label == this.label && heading == this.heading && bitmap === this.bitmap && thumbnails == this.thumbnails &&
            pictures == this.pictures && marks == this.marks
        ) return
        val sameLabel = label == this.label && heading == this.heading
        if (!sameLabel) nameLayout = null
        // A reused tile turning from a folder into a PDF, or back, changes height.
        if ((entry is FolderEntry) != (this.entry is FolderEntry)) requestLayout()
        // A file just written (say, the document just left) keeps its old picture until the
        // new one is drawn, rather than flashing grey for a moment on e-ink. Not after a
        // refresh: that's asked for when a picture looks wrong.
        val stillThisFile = thumbnails && this.thumbnails && entry.file == this.entry?.file && pictures == this.pictures
        val newBitmap = bitmap ?: this.bitmap.takeIf { stillThisFile }
        // Everything onDraw uses. A file just written is a new entry (new size and date) that
        // looks the same until its new thumbnail comes: no need to draw it twice.
        val looksTheSame = sameLabel && looksLike(entry, this.entry) && newBitmap === this.bitmap && thumbnails == this.thumbnails && marks == this.marks
        this.entry = entry
        this.label = label
        this.heading = heading
        this.bitmap = newBitmap
        this.thumbnails = thumbnails
        this.pictures = pictures
        this.marks = marks
        if (!looksTheSame) invalidate()
    }

    /** The marks changed for the entry on this tile: say, its thumbnail came, and with it whether it has writing. */
    override fun showMarks(marks: TileMarks) {
        if (marks == this.marks) return
        this.marks = marks
        invalidate()
    }

    /** Whether two entries draw the same tile, pictures and labels aside: a PDF shows only its label, a folder its label and counts. */
    private fun looksLike(a: Entry, b: Entry?): Boolean = when (a) {
        is PdfEntry -> b is PdfEntry
        is FolderEntry -> b is FolderEntry && a.pdfCount == b.pdfCount && a.folderCount == b.folderCount
    }

    /** A thumbnail arrived for the PDF on this tile, or null: it can't be drawn, so the grey page. */
    fun showBitmap(bitmap: Bitmap?) {
        if (bitmap === this.bitmap) return
        this.bitmap = bitmap
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val boxSide = width - 2 * pad
        val height = if (entry is FolderEntry) pad + markHeight(boxSide) + pad / 2 + 2 * nameLine + countGap + countLine + pad
        else pad + boxSide + pad / 2 + 2 * nameLine + pad
        setMeasuredDimension(width, height)
    }

    private fun markHeight(boxSide: Int) = (boxSide * FOLDER_MARK).toInt()

    /** Between a folder's name (its two lines' room) and its count, so a two-line name never touches it. */
    private val countGap = pad / 2

    /** The name is laid out for the tile's width: turning the tablet changes it. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) nameLayout = null
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate() // pressed on, pressed off
    }

    /** Grey as if pressed, while this tile's long-press menu is open (UI review, decision 4). */
    override var marked = false
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val entry = entry ?: return
        if (isPressed || marked) canvas.drawColor(LibraryLook.RULE)
        val boxBottom = if (entry is FolderEntry) pad + markHeight(width - 2 * pad) else width - pad
        box.set(pad.toFloat(), pad.toFloat(), (width - pad).toFloat(), boxBottom.toFloat())
        when (entry) {
            is FolderEntry -> drawFolder(canvas)
            is PdfEntry -> {
                drawPage(canvas)
                drawMarks(canvas)
            }
        }
        val textTop = box.bottom + pad / 2
        // With a heading (a PDF shown in place of its small folder): the folder's name on one
        // line in black, then the PDF's own short name under it in grey.
        val heading = heading
        val first = heading ?: label
        val layout = nameLayout ?: StaticLayout.Builder
            .obtain(first, 0, first.length, namePaint, (width - 2 * pad).coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(if (heading != null) 1 else 2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build().also { nameLayout = it }
        canvas.save()
        canvas.translate(pad.toFloat(), textTop)
        layout.draw(canvas)
        canvas.restore()
        if (heading != null) {
            val shown = TextUtils.ellipsize(label, subPaint, (width - 2 * pad).toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, width / 2f, textTop + nameLine - subPaint.fontMetrics.ascent, subPaint)
        }
        if (entry is FolderEntry) {
            canvas.drawText(folderCountText(entry), width / 2f, textTop + 2 * nameLine + countGap - countPaint.fontMetrics.ascent, countPaint)
        }
    }

    /** A folder: its body and the tab on top, outlined in black, [box] tall and centred across it. */
    private fun drawFolder(canvas: Canvas) {
        val h = box.height()
        val w = h * 1.3f
        val left = box.centerX() - w / 2
        val right = box.centerX() + w / 2
        val top = box.top
        val bottom = box.bottom
        val tab = h * 0.12f
        path.reset()
        path.moveTo(left, top + tab)
        path.lineTo(left, top)
        path.lineTo(left + w * 0.36f, top)
        path.lineTo(left + w * 0.44f, top + tab)
        path.lineTo(right, top + tab)
        path.lineTo(right, bottom)
        path.lineTo(left, bottom)
        path.close()
        line.style = Paint.Style.STROKE
        line.strokeWidth = 3f
        line.color = Color.BLACK
        canvas.drawPath(path, line)
        // Its course's colour (TileMarks.stripe), down the folder's left side, just clear of it.
        marks.stripe?.let { colour ->
            line.style = Paint.Style.FILL
            line.color = colour
            canvas.drawRect(left - 2 * stripeWidth, top, left - stripeWidth, bottom, line)
        }
    }

    private fun drawPage(canvas: Canvas) {
        val bitmap = bitmap
        if (bitmap != null) {
            fit(bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawBitmap(bitmap, null, page, picture)
            line.style = Paint.Style.STROKE
            line.strokeWidth = 2f
            line.color = LibraryLook.RULE
            canvas.drawRect(page, line)
            return
        }
        fit(1f, A4_RATIO)
        if (thumbnails) {
            // Waiting for the thumbnail: a plain grey page.
            line.style = Paint.Style.FILL
            line.color = LibraryLook.WAITING_PAGE
            canvas.drawRect(page, line)
            return
        }
        // Thumbnails off (decision 10): a drawn page with its corner folded down.
        val fold = page.width() * 0.22f
        path.reset()
        path.moveTo(page.left, page.top)
        path.lineTo(page.right - fold, page.top)
        path.lineTo(page.right, page.top + fold)
        path.lineTo(page.right, page.bottom)
        path.lineTo(page.left, page.bottom)
        path.close()
        path.moveTo(page.right - fold, page.top)
        path.lineTo(page.right - fold, page.top + fold)
        path.lineTo(page.right, page.top + fold)
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2f
        line.color = LibraryLook.GRAPHITE
        canvas.drawPath(path, line)
    }

    /**
     * On a PDF's picture ([page], as [drawPage] left it): a black corner when the PDF holds the
     * owner's writing, and under it a thin bar, black as far as the owner has got.
     */
    private fun drawMarks(canvas: Canvas) {
        line.style = Paint.Style.FILL
        if (marks.hasInk) {
            path.reset()
            path.moveTo(page.right - inkMark, page.top)
            path.lineTo(page.right, page.top)
            path.lineTo(page.right, page.top + inkMark)
            path.close()
            line.color = Color.BLACK
            canvas.drawPath(path, line)
        }
        val progress = marks.progress ?: return
        val top = page.bottom + 1f
        line.color = LibraryLook.RULE
        canvas.drawRect(page.left, top, page.right, top + barHeight, line)
        line.color = Color.BLACK
        canvas.drawRect(page.left, top, page.left + page.width() * progress, top + barHeight, line)
    }

    /** Sets [page] to a [w]×[h] shape scaled to fit in [box], centred. */
    private fun fit(w: Float, h: Float) {
        val scale = minOf(box.width() / w, box.height() / h)
        val pw = w * scale
        val ph = h * scale
        page.set(box.centerX() - pw / 2, box.centerY() - ph / 2, box.centerX() + pw / 2, box.centerY() + ph / 2)
    }

    private companion object {
        const val A4_RATIO = 1.414f
        /** A folder tile's mark band, as a share of the tile's inner width. */
        const val FOLDER_MARK = 0.4f
    }
}

/**
 * The top bar's path, `Documents › Year 2 › Physics` (decision 4): tap a part to go up to it.
 * A path too long for the bar scrolls so the folder on show stays in view.
 */
class BreadcrumbBar(context: Context, private val onTap: (File) -> Unit) : HorizontalScrollView(context) {
    private val parts = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(parts, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun show(root: File, folder: File) {
        parts.removeAllViews()
        val path = Breadcrumb.parts(root, folder)
        path.forEachIndexed { i, (name, target) ->
            if (i > 0) parts.addView(TextView(context).apply { text = "›"; textSize = 20f; setTextColor(LibraryLook.GRAPHITE) })
            val here = i == path.lastIndex
            parts.addView(TextView(context).apply {
                text = name
                textSize = 20f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER_VERTICAL
                val pad = LibraryLook.dp(context, 10)
                setPadding(pad, 0, pad, 0)
                if (here) typeface = Typeface.DEFAULT_BOLD
                else {
                    background = LibraryLook.pressedFeedback()
                    setOnClickListener { onTap(target) }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        post { fullScroll(FOCUS_RIGHT) }
    }
}
