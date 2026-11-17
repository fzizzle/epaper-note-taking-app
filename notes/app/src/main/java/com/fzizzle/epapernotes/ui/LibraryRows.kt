package com.fzizzle.epapernotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import com.fzizzle.epapernotes.library.Entry
import com.fzizzle.epapernotes.library.FolderEntry
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.library.TileMarks

/** What the library's tiles and rows share, so the grid can treat them alike. */
interface LibraryItemView {
    val entry: Entry?

    /** Grey as if pressed: its long-press menu is open, or it's the document just come back from. */
    var marked: Boolean

    fun showMarks(marks: TileMarks)
}

/**
 * One line of the library's list view (decision 6): a small folder or page mark, the name, and
 * at the right how far the owner is ("12 / 30" over the progress bar) or what a folder holds.
 * No pictures: quicker to scan when the first pages all look alike, and quicker to draw.
 */
class RowView(context: Context) : View(context), LibraryItemView {
    override var entry: Entry? = null
        private set
    private var name = ""
    private var detail = ""
    private var marks = TileMarks.NONE

    private val pad = LibraryLook.dp(context, 12).toFloat()
    private val rowHeight = LibraryLook.dp(context, ROW_HEIGHT_DP)
    private val markSide = LibraryLook.dp(context, 26).toFloat()
    private val barWidth = LibraryLook.dp(context, 96).toFloat()
    private val barHeight = LibraryLook.dp(context, 3).toFloat()
    private val stripeWidth = LibraryLook.dp(context, 4).toFloat()
    private val inkDot = LibraryLook.dp(context, 5).toFloat()
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, resources.displayMetrics)
    }
    private val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LibraryLook.GRAPHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        textAlign = Paint.Align.RIGHT
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** [detail]: "12 / 30", "30 pages", "4 PDFs"; empty for none. Redraws only when something it shows changed. */
    fun show(entry: Entry, name: String, detail: String, marks: TileMarks) {
        val same = name == this.name && detail == this.detail && marks == this.marks && (entry is FolderEntry) == (this.entry is FolderEntry)
        this.entry = entry
        this.name = name
        this.detail = detail
        this.marks = marks
        if (!same) invalidate()
    }

    override fun showMarks(marks: TileMarks) {
        if (marks == this.marks) return
        this.marks = marks
        invalidate()
    }

    override var marked = false
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rowHeight)

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate() // pressed on, pressed off
    }

    override fun onDraw(canvas: Canvas) {
        val entry = entry ?: return
        if (isPressed || marked) canvas.drawColor(LibraryLook.RULE)
        val cy = height / 2f
        marks.stripe?.let {
            line.style = Paint.Style.FILL
            line.color = it
            canvas.drawRect(0f, pad / 2, stripeWidth, height - pad / 2, line)
        }
        val markLeft = pad + stripeWidth
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2.5f
        line.color = Color.BLACK
        if (entry is FolderEntry) drawFolderMark(canvas, markLeft, cy) else drawPageMark(canvas, markLeft, cy)

        // The right side first, so the name knows how much room is left.
        val right = width - pad
        var nameRight = right
        if (detail.isNotEmpty()) {
            val hasBar = marks.progress != null
            val textY = if (hasBar) cy - barHeight else cy - (detailPaint.ascent() + detailPaint.descent()) / 2
            // A search result's folder path can be long: it keeps its end, the folder the PDF is in.
            val shown = shortenedFromStart(detail, width * DETAIL_SHARE, detailPaint::measureText)
            canvas.drawText(shown, right, textY, detailPaint)
            nameRight = right - maxOf(detailPaint.measureText(shown), if (hasBar) barWidth else 0f) - pad
            marks.progress?.let { progress ->
                val top = cy + barHeight
                line.style = Paint.Style.FILL
                line.color = LibraryLook.RULE
                canvas.drawRect(right - barWidth, top, right, top + barHeight, line)
                line.color = Color.BLACK
                canvas.drawRect(right - barWidth, top, right - barWidth + barWidth * progress, top + barHeight, line)
            }
        }
        var nameLeft = markLeft + markSide + pad
        if (marks.hasInk) {
            // The writing mark: a black dot before the name.
            line.style = Paint.Style.FILL
            line.color = Color.BLACK
            canvas.drawCircle(nameLeft + inkDot, cy, inkDot, line)
            nameLeft += 2 * inkDot + pad / 2
        }
        val shown = TextUtils.ellipsize(name, namePaint, (nameRight - nameLeft).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, nameLeft, cy - (namePaint.ascent() + namePaint.descent()) / 2, namePaint)
        // A hairline under each row.
        line.style = Paint.Style.FILL
        line.color = LibraryLook.RULE
        canvas.drawRect(pad, height - 1f, width - pad, height.toFloat(), line)
    }

    private companion object {
        /** The most of the row's width the right-hand words take. */
        const val DETAIL_SHARE = 0.45f
    }

    private fun drawFolderMark(canvas: Canvas, left: Float, cy: Float) {
        val w = markSide
        val h = markSide * 0.75f
        val top = cy - h / 2
        val tab = h * 0.14f
        path.reset()
        path.moveTo(left, top + tab)
        path.lineTo(left, top)
        path.lineTo(left + w * 0.36f, top)
        path.lineTo(left + w * 0.44f, top + tab)
        path.lineTo(left + w, top + tab)
        path.lineTo(left + w, top + h)
        path.lineTo(left, top + h)
        path.close()
        canvas.drawPath(path, line)
    }

    private fun drawPageMark(canvas: Canvas, left: Float, cy: Float) {
        val w = markSide * 0.72f
        val h = markSide
        val x = left + (markSide - w) / 2
        val top = cy - h / 2
        val fold = w * 0.3f
        path.reset()
        path.moveTo(x, top)
        path.lineTo(x + w - fold, top)
        path.lineTo(x + w, top + fold)
        path.lineTo(x + w, top + h)
        path.lineTo(x, top + h)
        path.close()
        canvas.drawPath(path, line)
    }
}

/**
 * [detail] if it fits in [room], else shortened from its start: whole folders first
 * ("… › Topic"), then, if even the last folder is too wide, letters.
 */
internal fun shortenedFromStart(detail: String, room: Float, width: (String) -> Float): String {
    if (width(detail) <= room) return detail
    val parts = detail.split(FOLDER_SEPARATOR)
    for (i in 1 until parts.size) {
        val shorter = ELLIPSIS + FOLDER_SEPARATOR + parts.drop(i).joinToString(FOLDER_SEPARATOR)
        if (width(shorter) <= room) return shorter
    }
    // Letters from the end, as many as fit after the "…".
    val last = parts.last()
    for (start in 1..last.length) {
        val shorter = ELLIPSIS + last.substring(start)
        if (width(shorter) <= room) return shorter
    }
    return ELLIPSIS
}

/** A list row's height, in dp. */
internal const val ROW_HEIGHT_DP = 56

private const val FOLDER_SEPARATOR = " › "
private const val ELLIPSIS = "…"
