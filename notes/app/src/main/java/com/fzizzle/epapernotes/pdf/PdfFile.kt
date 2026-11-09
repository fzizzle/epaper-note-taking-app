package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.util.SizeF
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.doc.Change
import com.fzizzle.epapernotes.doc.PdfPages
import com.fzizzle.epapernotes.doc.RemoveItems
import com.fzizzle.epapernotes.doc.ReplaceItems
import com.fzizzle.epapernotes.doc.RotatePages
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.Item
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

    private val annotations = ItemAnnotations(doc)

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
    override fun rotation(page: Int): Int {
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

    /** The app's own items on a page, in drawing order. Other annotations are left out (see [foreignAnnotationCount]). */
    override fun items(page: Int): List<Item> = annotationDicts(page).mapNotNull { annotations.read(it) }

    /**
     * Every annotation on the page that isn't one of the app's items: highlights and notes
     * from other apps, links, form fields. They're counted from the page's raw /Annots list,
     * because MuPDF's annotation list leaves links out.
     */
    fun foreignAnnotationCount(page: Int): Int = annotationDicts(page).count { !annotations.isOurs(it) }

    /**
     * An add skips ids already on the page, so a replay can't double an item, and a move only
     * moves items still on the page ([ReplaceItems.stillThere]), so it can't bring back an
     * erased one.
     */
    override fun apply(change: Change) {
        when (change) {
            is AddItems -> onPage(change.page) { p ->
                val there = idsOn(change.page)
                change.items.filter { it.id !in there }.forEach { annotations.add(p, it) }
            }
            is RemoveItems -> onPage(change.page) { p -> remove(p, change.items) }
            is ReplaceItems -> onPage(change.page) { p ->
                val move = change.stillThere(idsOn(change.page))
                remove(p, move.before)
                move.after.forEach { annotations.add(p, it) }
            }
            is RotatePages -> change.after.forEach { (page, degrees) -> if (page in 0 until pageCount) setRotation(page, degrees) }
        }
    }

    /**
     * The page as shown (rotation applied, ink included), cut to [displayBox] in display points
     * (origin top-left, y down), at [pxPerPt] pixels per point. Used for snips.
     */
    fun renderRegion(page: Int, displayBox: Box, pxPerPt: Float): Bitmap {
        val p = doc.loadPage(page)
        try {
            val w = Math.round((displayBox.x1 - displayBox.x0) * pxPerPt).coerceAtLeast(1)
            val h = Math.round((displayBox.y1 - displayBox.y0) * pxPerPt).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // MuPDF's page space is the page as shown, so scaling it gives display pixels. The
            // device's origin says which of those pixels lands at the bitmap's top-left.
            val dev = AndroidDrawDevice(bmp, Math.round(displayBox.x0 * pxPerPt), Math.round(displayBox.y0 * pxPerPt))
            try {
                p.run(dev, Matrix.Scale(pxPerPt), null)
                dev.close()
            } finally {
                dev.destroy()
            }
            return bmp
        } finally {
            p.destroy()
        }
    }

    /** Runs [edit] on a loaded page, then lets MuPDF catch up. A page that doesn't exist is skipped. */
    private fun onPage(page: Int, edit: (PDFPage) -> Unit) {
        if (page !in 0 until pageCount) return
        val p = doc.loadPage(page) as PDFPage
        try {
            edit(p)
            p.update()
        } finally {
            p.destroy()
        }
    }

    /** Deletes the app's annotations with these items' ids. Ids that aren't there are skipped. */
    private fun remove(p: PDFPage, items: List<Item>) {
        val ids = items.mapTo(HashSet()) { it.id }
        p.annotations.orEmpty()
            .filter { annotations.isOurs(it.getObject()) && annotations.idOf(it.getObject()) in ids }
            .forEach { p.deleteAnnotation(it) }
    }

    /**
     * Writes the whole document, with all changes, to [target].
     *
     * "compress" squeezes any streams that aren't compressed yet, which here means the
     * items' drawings. Without it every stroke adds about 60 KB, which would swell a
     * well-used lecture file by tens of megabytes. Streams that are already compressed are
     * left as they are.
     *
     * "garbage" drops objects nothing refers to any more. Deleting an annotation only unlinks
     * it, so without this, erased strokes would stay in the file for good. Only level 1:
     * higher levels renumber objects, which would change the ids of plan-2 strokes (`o<n>`).
     *
     * "objstms" packs small objects into compressed object streams. Each stroke keeps its
     * point list and its private stroke data as text, and that text would otherwise be stored
     * uncompressed.
     */
    override fun saveTo(target: File) {
        doc.save(target.path, "compress,garbage,objstms")
    }

    override fun close() = doc.destroy()

    /** The ids of the app's items on [page], read without decoding any of them. */
    private fun idsOn(page: Int): Set<String> = annotationDicts(page).mapNotNullTo(HashSet()) { d ->
        if (annotations.isOurs(d)) annotations.idOf(d) else null
    }

    private fun annotationDicts(page: Int): List<PDFObject> {
        val annots = doc.findPage(page).get("Annots")
        if (!annots.isArray) return emptyList()
        return (0 until annots.size()).map { annots.get(it) }.filter { it.isDictionary }
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
            // asPDF returns a second handle with its own reference, so the first one is let go
            // now. Otherwise the document, and its open file, would linger until the garbage
            // collector got round to it.
            val pdf = try { opened.asPDF() } finally { opened.destroy() }
                ?: throw PdfOpenException("${file.name} is not a PDF.")
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
