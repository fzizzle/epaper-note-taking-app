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

/**
 * The grid's tiles, reused as it scrolls. [bitmapFor] only looks in memory (ThumbnailLoader),
 * so binding a tile never touches the disk. With [thumbnails] off, PDF tiles are drawn pages.
 */
class LibraryAdapter(private val bitmapFor: (PdfEntry) -> Bitmap?) : BaseAdapter() {
    var entries: List<Entry> = emptyList()
        private set
    var thumbnails = true
        private set

    fun show(entries: List<Entry>, thumbnails: Boolean) {
        this.entries = entries
        this.thumbnails = thumbnails
        notifyDataSetChanged()
    }

    override fun getCount() = entries.size
    override fun getItem(position: Int) = entries[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val tile = convertView as? TileView ?: TileView(parent.context).apply {
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val entry = entries[position]
        tile.show(entry, if (thumbnails && entry is PdfEntry) bitmapFor(entry) else null, thumbnails)
        return tile
    }
}

/**
 * One tile, drawn with plain shapes: a square box holding the PDF's first page (or a grey page
 * while it's on its way, or a drawn page with thumbnails off) or a folder shape, then the name
 * on up to two lines, and for a folder how many PDFs are directly inside (decision 2). Every
 * tile is the same height, so the rows line up.
 */
class TileView(context: Context) : View(context) {
    var entry: Entry? = null
        private set
    private var bitmap: Bitmap? = null
    private var thumbnails = true

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
    private val nameLine = namePaint.getFontMetricsInt(null)
    private val countLine = countPaint.getFontMetricsInt(null)
    private var nameLayout: StaticLayout? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private val page = RectF()
    private val path = Path()

    fun show(entry: Entry, bitmap: Bitmap?, thumbnails: Boolean) {
        if (entry == this.entry && bitmap === this.bitmap && thumbnails == this.thumbnails) return
        if (entry.name != this.entry?.name) nameLayout = null
        // A file just written (say, the document just left) keeps its old picture until the
        // new one is drawn, rather than flashing grey for a moment on e-ink.
        val stillThisFile = thumbnails && this.thumbnails && entry.file == this.entry?.file
        this.entry = entry
        this.bitmap = bitmap ?: this.bitmap.takeIf { stillThisFile }
        this.thumbnails = thumbnails
        invalidate()
    }

    /** A thumbnail arrived for the PDF on this tile. */
    fun showBitmap(bitmap: Bitmap) {
        if (bitmap === this.bitmap) return
        this.bitmap = bitmap
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val boxSide = width - 2 * pad
        setMeasuredDimension(width, pad + boxSide + pad / 2 + 2 * nameLine + countLine + pad)
    }

    /** The name is laid out for the tile's width: turning the tablet changes it. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) nameLayout = null
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate() // pressed on, pressed off
    }

    override fun onDraw(canvas: Canvas) {
        val entry = entry ?: return
        if (isPressed) canvas.drawColor(LibraryLook.RULE)
        box.set(pad.toFloat(), pad.toFloat(), (width - pad).toFloat(), (width - pad).toFloat())
        when (entry) {
            is FolderEntry -> drawFolder(canvas)
            is PdfEntry -> drawPage(canvas)
        }
        val textTop = box.bottom + pad / 2
        val layout = nameLayout ?: StaticLayout.Builder
            .obtain(entry.name, 0, entry.name.length, namePaint, (width - 2 * pad).coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build().also { nameLayout = it }
        canvas.save()
        canvas.translate(pad.toFloat(), textTop)
        layout.draw(canvas)
        canvas.restore()
        if (entry is FolderEntry) {
            val count = when (entry.pdfCount) { 0 -> "No PDFs"; 1 -> "1 PDF"; else -> "${entry.pdfCount} PDFs" }
            canvas.drawText(count, width / 2f, textTop + 2 * nameLine - countPaint.fontMetrics.ascent, countPaint)
        }
    }

    /** A folder: its body and the tab on top, outlined in black. */
    private fun drawFolder(canvas: Canvas) {
        val w = box.width()
        val left = box.left + w * 0.06f
        val right = box.right - w * 0.06f
        val top = box.top + w * 0.22f
        val bottom = box.bottom - w * 0.1f
        val tab = w * 0.08f
        path.reset()
        path.moveTo(left, top + tab)
        path.lineTo(left, top)
        path.lineTo(left + w * 0.32f, top)
        path.lineTo(left + w * 0.4f, top + tab)
        path.lineTo(right, top + tab)
        path.lineTo(right, bottom)
        path.lineTo(left, bottom)
        path.close()
        line.style = Paint.Style.STROKE
        line.strokeWidth = 3f
        line.color = Color.BLACK
        canvas.drawPath(path, line)
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

    /** Sets [page] to a [w]×[h] shape scaled to fit in [box], centred. */
    private fun fit(w: Float, h: Float) {
        val scale = minOf(box.width() / w, box.height() / h)
        val pw = w * scale
        val ph = h * scale
        page.set(box.centerX() - pw / 2, box.centerY() - ph / 2, box.centerX() + pw / 2, box.centerY() + ph / 2)
    }

    private companion object {
        const val A4_RATIO = 1.414f
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
