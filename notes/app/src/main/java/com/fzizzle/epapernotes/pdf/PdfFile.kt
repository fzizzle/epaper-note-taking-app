package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.util.SizeF
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.fzizzle.epapernotes.doc.PdfPages
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeCodec
import com.fzizzle.epapernotes.ink.StrokeOutline
import com.fzizzle.epapernotes.ink.StrokeStyle
import java.io.File

/**
 * One open PDF. This is the only class in the app that talks to MuPDF, so the rest of the
 * app never deals with MuPDF's types.
 *
 * Not thread-safe: MuPDF's objects must not be used from two threads at once. Use each
 * PdfFile from a single thread. (The writing screen will give each open document its own
 * worker thread.)
 *
 * It never writes over its own source file. [saveTo] writes a new file, and the caller swaps
 * it in safely (see SafeSave).
 */
class PdfFile private constructor(private val doc: PDFDocument) : PdfPages {

    override val pageCount: Int get() = doc.countPages()

    /**
     * True when MuPDF had to repair the file, while opening it or later while reading it.
     * Saving would then replace the original with the repaired version, so the owner is asked
     * first (spec §7.3). Asked fresh each time, because the repair can happen late.
     */
    override val wasRepaired: Boolean get() = doc.wasRepaired()

    /**
     * Maps PDF user space (where strokes are stored) to display points: the page as shown,
     * rotation applied, origin at the top-left corner, y pointing down.
     */
    fun pdfToDisplay(page: Int): Affine {
        val p = doc.loadPage(page) as PDFPage
        try {
            val m = p.transform
            return Affine(m.a, m.b, m.c, m.d, m.e, m.f)
        } finally {
            p.destroy()
        }
    }

    /** Page size in points as shown, with its rotation applied. */
    fun displaySize(page: Int): SizeF {
        val p = doc.loadPage(page)
        try {
            val b = p.bounds
            return SizeF(b.x1 - b.x0, b.y1 - b.y0)
        } finally {
            p.destroy()
        }
    }

    /** The page with all its annotations drawn, scaled to [widthPx] pixels wide. */
    fun render(page: Int, widthPx: Int): Bitmap {
        val p = doc.loadPage(page)
        try {
            return AndroidDrawDevice.drawPageFitWidth(p, widthPx)
        } finally {
            p.destroy()
        }
    }

    /**
     * The page's rotation: 0, 90, 180 or 270. Many PDFs set /Rotate once on the page tree
     * rather than on each page, so a missing value is looked up through the /Parent links.
     * MuPDF's own lookup does that. In a damaged file whose tree loops back on itself it
     * throws instead of going round forever, and the page is then shown unrotated.
     */
    fun rotation(page: Int): Int {
        val r = try {
            doc.findPage(page).getInheritable("Rotate")
        } catch (e: RuntimeException) {
            return 0
        }
        return if (r.isNumber) normalise(r.asInteger()) else 0
    }

    /** Rotates the page. It's written on the page itself, which overrides any inherited value. */
    fun setRotation(page: Int, degrees: Int) {
        doc.findPage(page).put("Rotate", normalise(degrees))
    }

    /** One of the app's own strokes, as found on a page. [id] is its PDF object number, stable across saves. */
    data class StoredStroke(val id: Int, val stroke: Stroke)

    /** The app's own strokes on a page. Other annotations are left out (see [foreignAnnotationCount]). */
    fun strokes(page: Int): List<StoredStroke> = annotationDicts(page).mapNotNull { dict ->
        val text = dict.get(StrokeCodec.KEY)
        if (!text.isString) return@mapNotNull null
        val stroke = StrokeCodec.decode(text.asString()) ?: return@mapNotNull null
        StoredStroke(dict.asIndirect(), stroke)
    }

    /**
     * Every annotation on the page that isn't one of the app's strokes: highlights and notes
     * from other apps, links, form fields. They're counted from the page's raw /Annots list,
     * because MuPDF's annotation list leaves links out.
     */
    fun foreignAnnotationCount(page: Int): Int = annotationDicts(page).count { !isOurs(it) }

    /** Adds [stroke] to the page as a standard PDF ink annotation and returns its id. */
    override fun addStroke(page: Int, stroke: Stroke): Int {
        val p = doc.loadPage(page) as PDFPage
        try {
            val style = StrokeStyle.of(stroke.kind)
            val annot = p.createAnnotation(PDFAnnotation.TYPE_INK)

            // The ink list and colour are for viewers that ignore appearances and redraw ink
            // themselves. MuPDF's ink-list API works in its page space, not PDF space.
            val toPageSpace = p.transform
            annot.setInkList(arrayOf(stroke.points.map { Point(it.x, it.y).transform(toPageSpace) }.toTypedArray()))
            annot.setColor(style.rgb)
            annot.setOpacity(style.opacity)
            annot.setBorderWidth(style.maxWidth)
            annot.getObject().put(StrokeCodec.KEY, StrokeCodec.encode(stroke))
            annot.update()

            // Replace MuPDF's fixed-width drawing with the pressure outline. The outline is in
            // PDF space, and setAppearance expects page space, so the page transform maps it there.
            val box = StrokeOutline.bounds(stroke)
            annot.setAppearance(toPageSpace, Rect(box.x0, box.y0, box.x1, box.y1), resources(style.opacity), contents(stroke))
            p.update()
            return annot.getObject().asIndirect()
        } finally {
            p.destroy()
        }
    }

    /** Removes one of the app's strokes. An id that isn't one of the app's strokes is ignored. */
    fun deleteStroke(page: Int, id: Int) {
        val p = doc.loadPage(page) as PDFPage
        try {
            val annot = p.annotations?.firstOrNull { it.getObject().asIndirect() == id && isOurs(it.getObject()) } ?: return
            p.deleteAnnotation(annot)
            p.update()
        } finally {
            p.destroy()
        }
    }

    /**
     * Writes the whole document, with all changes, to [target].
     *
     * "compress" squeezes any streams that aren't compressed yet, which here means the
     * strokes' drawings. Without it every stroke adds about 60 KB, which would swell a
     * well-used lecture file by tens of megabytes. Streams that are already compressed are
     * left as they are.
     *
     * "garbage" drops objects nothing refers to any more. Deleting an annotation only unlinks
     * it, so without this, erased strokes would stay in the file for good. Only level 1:
     * higher levels renumber objects, which would change the stroke ids.
     *
     * "objstms" packs small objects into compressed object streams. Each stroke keeps its
     * point list and its private stroke data as text, and that text would otherwise be stored
     * uncompressed.
     */
    override fun saveTo(target: File) {
        doc.save(target.path, "compress,garbage,objstms")
    }

    override fun close() = doc.destroy()

    private fun annotationDicts(page: Int): List<PDFObject> {
        val annots = doc.findPage(page).get("Annots")
        if (!annots.isArray) return emptyList()
        return (0 until annots.size()).map { annots.get(it) }.filter { it.isDictionary }
    }

    private fun isOurs(dict: PDFObject) =
        dict.get("Subtype").asName() == "Ink" && dict.get(StrokeCodec.KEY).isString

    /** An ExtGState that sets fill opacity, so the highlighter stays see-through in any viewer. */
    private fun resources(opacity: Float): PDFObject {
        val gs = doc.newDictionary()
        gs.put("ca", opacity)
        val ext = doc.newDictionary()
        ext.put("GS0", gs)
        val res = doc.newDictionary()
        res.put("ExtGState", ext)
        return res
    }

    private fun contents(stroke: Stroke): Buffer {
        val buf = Buffer()
        buf.writeBytes(("/GS0 gs\n" + StrokeOutline.contentStream(stroke)).toByteArray(Charsets.US_ASCII))
        return buf
    }

    /** Rotation in 90° steps, turned into 0..270. Negative values and multiples of 360 are allowed in PDFs. */
    private fun normalise(degrees: Int): Int {
        val quarter = Math.floorMod(Math.round(degrees / 90f), 4)
        return quarter * 90
    }

    companion object {
        fun open(file: File): PdfFile {
            val opened = try {
                Document.openDocument(file.path)
            } catch (e: RuntimeException) {
                throw PdfOpenException("Can't open ${file.name}: it may be damaged or not a PDF.", e)
            }
            if (opened.needsPassword()) {
                opened.destroy()
                throw PdfOpenException("${file.name} is password-protected.")
            }
            val pdf = opened.asPDF()
            if (pdf == null) {
                opened.destroy()
                throw PdfOpenException("${file.name} is not a PDF.")
            }
            // MuPDF repairs damaged files as it opens them. One with no usable pages left is
            // refused here, rather than failing later in the middle of drawing.
            val pages = try { pdf.countPages() } catch (e: RuntimeException) { 0 }
            if (pages == 0) {
                pdf.destroy()
                throw PdfOpenException("${file.name} has no readable pages: it may be damaged.")
            }
            return PdfFile(pdf)
        }
    }
}
