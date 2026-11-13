package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.util.SizeF
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.RectI
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.doc.Change
import com.fzizzle.epapernotes.doc.InkRedraw
import com.fzizzle.epapernotes.doc.PdfPages
import com.fzizzle.epapernotes.doc.RemoveItems
import com.fzizzle.epapernotes.doc.ReplaceItems
import com.fzizzle.epapernotes.doc.RotatePages
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.InkItem
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

    /**
     * A page's size as shown and its PDF-to-display transform, the same as [displaySize] and
     * [pdfToDisplay] give, from loading the page once instead of twice. Laying out a long
     * document asks for both for every page.
     */
    fun layout(page: Int): Pair<SizeF, Affine> {
        val p = doc.loadPage(page) as PDFPage
        try {
            val b = p.bounds
            val m = p.transform
            return SizeF(b.x1 - b.x0, b.y1 - b.y0) to Affine(m.a, m.b, m.c, m.d, m.e, m.f)
        } finally {
            p.destroy()
        }
    }

    /**
     * The page with all its annotations drawn, scaled to [widthPx] pixels wide.
     *
     * Ink at full strength, the owner's pen among it, has a hard edge unless [smoothInk]:
     * solid with no grey rim, like Onyx's live ink. A second or so after the pen lifts, Onyx
     * hands the panel back to the app's drawing, and a smoothed stroke, whose rim was 45% of
     * its pixels, looked thinner than the live ink it replaced (owner, 2026-11-10). Everything
     * else (the page, the highlighter, other annotations) stays smoothed, and so do thumbnails:
     * scaled down, a hard edge looks ragged.
     */
    fun render(page: Int, widthPx: Int, smoothInk: Boolean = false): Bitmap {
        val p = doc.loadPage(page) as PDFPage
        try {
            if (smoothInk) return AndroidDrawDevice.drawPageFitWidth(p, widthPx)
            val ctm = AndroidDrawDevice.fitPageWidth(p, widthPx)
            val box = RectI(p.bounds.transform(ctm))
            val bmp = Bitmap.createBitmap(box.x1 - box.x0, box.y1 - box.y0, Bitmap.Config.ARGB_8888)
            drawWithSolidInk(p, bmp, box, ctm)
            return bmp
        } finally {
            p.destroy()
        }
    }

    /**
     * Draws [p] with [ctm] onto [bmp], whose pixels are [box] of the page's device space:
     * everything smoothed, as MuPDF draws it, except ink at full strength, which is drawn
     * solid ([drawSolid]; see [render] for why).
     */
    private fun drawWithSolidInk(p: PDFPage, bmp: Bitmap, box: RectI, ctm: Matrix) {
        val annots = p.annotations ?: emptyArray()
        val isSolid = BooleanArray(annots.size) { annots[it].type == PDFAnnotation.TYPE_INK && annots[it].opacity >= 0.99f }
        // The usual order, with the solid ink left out: the page, annotations, form fields.
        val dev = AndroidDrawDevice(bmp, box.x0, box.y0)
        try {
            p.runPageContents(dev, ctm, null)
            annots.forEachIndexed { i, a -> if (!isSolid[i]) a.run(dev, ctm, null) }
            p.runPageWidgets(dev, ctm, null)
            dev.close()
        } finally {
            dev.destroy()
        }
        if (isSolid.any { it }) drawSolid(bmp, box, annots.filterIndexed { i, _ -> isSolid[i] }, ctm)
    }

    /**
     * Draws [ink] over [bmp] with a hard edge. MuPDF in this build always smooths (its
     * smoothing level can't be changed), so the ink is drawn on its own onto a clear picture
     * of the area it covers, and every pixel it covers at least half of is painted in full,
     * in the ink's own colour; the rest is left as the page. Half is where a hard-edged line
     * of the same width would put its edge, so the ink keeps its width.
     */
    private fun drawSolid(bmp: Bitmap, box: RectI, ink: List<PDFAnnotation>, ctm: Matrix) {
        // Where each stroke is, in the picture's pixels, so a band only draws the strokes that reach into it.
        val bounds = ink.map { RectI(it.bounds.transform(ctm)) }
        val x0 = maxOf(bounds.minOf { it.x0 }, box.x0)
        val x1 = minOf(bounds.maxOf { it.x1 }, box.x1)
        val top = maxOf(bounds.minOf { it.y0 }, box.y0)
        val bottom = minOf(bounds.maxOf { it.y1 }, box.y1)
        val w = x1 - x0
        if (w <= 0 || bottom <= top) return
        // A band of rows at a time, not the ink's whole area at once: that is usually the whole
        // page, and it took three buffers of that size (MuPDF's picture, its bytes, the pixels):
        // about 60 MB for a fitted page, 130 MB for the widest. One pixel buffer serves every band.
        val px = IntArray(w * minOf(SOLID_BAND_ROWS, bottom - top))
        var y0 = top
        while (y0 < bottom) {
            val h = minOf(SOLID_BAND_ROWS, bottom - y0)
            val inBand = ink.filterIndexed { i, _ -> bounds[i].y0 < y0 + h && bounds[i].y1 > y0 }
            if (inBand.isNotEmpty()) drawSolidBand(bmp, box, inBand, ctm, x0, y0, w, h, px)
            y0 += h
        }
    }

    /** [drawSolid] for the rows [y0] until [y0] + [h] of the ink's area; [px] has room for them. */
    private fun drawSolidBand(bmp: Bitmap, box: RectI, ink: List<PDFAnnotation>, ctm: Matrix, x0: Int, y0: Int, w: Int, h: Int, px: IntArray) {
        // Drawn with a few rows to spare above and below (SOLID_BAND_MARGIN); only the band's own rows are used.
        val m = SOLID_BAND_MARGIN
        val layer = Pixmap(ColorSpace.DeviceRGB, x0, y0 - m, w, h + 2 * m, true)
        try {
            layer.clear() // transparent
            val dev = DrawDevice(layer)
            try {
                ink.forEach { it.run(dev, ctm, null) }
                dev.close()
            } finally {
                dev.destroy()
            }
            val s = layer.samples // r, g, b, alpha per pixel, colour multiplied by alpha
            val stride = layer.stride
            bmp.getPixels(px, 0, w, x0 - box.x0, y0 - box.y0, w, h)
            for (y in 0 until h) {
                var o = (y + m) * stride
                var i = y * w
                for (x in 0 until w) {
                    val a = s[o + 3].toInt() and 0xff
                    if (a >= HALF) {
                        px[i] = (0xff shl 24) or
                            (((s[o].toInt() and 0xff) * 255 / a) shl 16) or
                            (((s[o + 1].toInt() and 0xff) * 255 / a) shl 8) or
                            ((s[o + 2].toInt() and 0xff) * 255 / a)
                    }
                    o += 4
                    i++
                }
            }
            bmp.setPixels(px, 0, w, x0 - box.x0, y0 - box.y0, w, h)
        } finally {
            layer.destroy()
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

    /**
     * The new page is drawn with /Rotate 0 and the size page [after] is shown at, so it looks
     * the same shape as its neighbour even when that one is turned. Its dots are its own page
     * content: a plain drawing every viewer shows, not one of the app's items.
     *
     * It gets a /CropBox of its own, the whole page: a CropBox set on the page tree would
     * otherwise be inherited by the new page and cut it down to part of itself.
     */
    override fun addDottedPage(after: Int) {
        require(after in 0 until pageCount) { "no page $after" }
        val size = displaySize(after)
        val content = DottedPaper.content(size.width, size.height)
        val page = doc.addPage(Rect(0f, 0f, size.width, size.height), 0, doc.newDictionary(), content)
        // Numbers go in with push(Float), never push(PDFObject), which fitz 1.28.5 frees too early.
        val crop = doc.newArray()
        listOf(0f, 0f, size.width, size.height).forEach { crop.push(it) }
        page.put("CropBox", crop)
        page.put(ADDED_PAGE_KEY, true)
        doc.insertPage(after + 1, page)
        forgetAllPages()
    }

    /** A page without the key, which is every page that came with the PDF, answers false. */
    override fun isAddedPage(page: Int): Boolean = doc.findPage(page).get(ADDED_PAGE_KEY).asBoolean()

    override fun removeAddedPage(page: Int) {
        require(pageCount > 1) { "page $page is the only page" }
        require(isAddedPage(page)) { "page $page wasn't added by the app" }
        doc.deletePage(page)
        forgetAllPages()
    }

    /**
     * The ids of the app's items on each page that has been edited, so adding a stroke doesn't
     * walk through every stroke already there to check for its id (on a page of 1,500 strokes
     * that walk took ten times as long as the adding). Read from the PDF the first time a page
     * is edited ([idsFor]), then kept in step by every add and remove here.
     */
    private val idsByPage = HashMap<Int, HashSet<String>>()

    /**
     * Each page's items as [items] last gave them, in drawing order, for the few pages used
     * most recently. Every erase and lasso asks for all of a page's items, and decoding a full
     * page takes about a second. Kept in step by every add and remove here, so it always
     * holds exactly what reading the PDF again would give ([readItems]).
     *
     * Only a few pages, because a page's items are all its strokes' points: the owner erases
     * on the page in view, and a whole lecture's worth would be a lot of memory.
     *
     * Both this and [idsByPage] are dropped for a page whenever they might no longer be right:
     * see [forgetPage].
     */
    private val itemsByPage = object : LinkedHashMap<Int, ArrayList<Item>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ArrayList<Item>>) = size > ITEM_PAGES_KEPT
    }

    /**
     * The app's own items on a page, in drawing order. Other annotations are left out (see
     * [foreignAnnotationCount]). The caller gets a list of its own: later changes don't alter it.
     */
    override fun items(page: Int): List<Item> =
        itemsByPage.getOrPut(page) { readItems(page).toCollection(ArrayList()) }.toList()

    override fun hasOwnItems(page: Int): Boolean = idsFor(page).isNotEmpty()

    /** A page's items decoded from the PDF itself, every time. [items] is the one to use; tests compare the two. */
    internal fun readItems(page: Int): List<Item> = annotationDicts(page).mapNotNull { annotations.read(it) }

    /** How many pictures had to be encoded from the PDF since it was opened. For tests. */
    internal val pictureEncodes get() = annotations.pictureEncodes

    /** Next time, read this page from the PDF again. */
    private fun forgetPage(page: Int) {
        idsByPage.remove(page)
        itemsByPage.remove(page)
    }

    /** A page was added or removed, so every later page has a new number: read them all from the PDF again. */
    private fun forgetAllPages() {
        idsByPage.clear()
        itemsByPage.clear()
    }

    /**
     * Every annotation on the page that isn't one of the app's items: highlights and notes
     * from other apps, links, form fields. They're counted from the page's raw /Annots list,
     * because MuPDF's annotation list leaves links out.
     */
    fun foreignAnnotationCount(page: Int): Int = annotationDicts(page).count { !annotations.isOurs(it) }

    /** Strokes whose redraw failed while this file is open, by id: tried again only at the next opening. */
    private val notRedrawable = HashSet<String>()

    /**
     * Each stroke is tried on its own, so one that can't be read or drawn leaves the others
     * redrawn, and counted, so they're saved. Only strokes really drawn again are counted.
     */
    override fun redrawFaultyInk(page: Int, limit: Int): InkRedraw {
        val faulty = annotationDicts(page).filter { annotations.isFaulty(it) && annotations.idOf(it) !in notRedrawable }
        var redrawn = 0
        for (dict in faulty.take(limit)) {
            val ok = try {
                annotations.redraw(dict)
            } catch (e: RuntimeException) {
                System.err.println("PdfFile: redrawing a stroke on page $page failed: $e")
                false
            }
            if (ok) redrawn++ else annotations.idOf(dict)?.let { notRedrawable += it }
        }
        return InkRedraw(redrawn, more = faulty.size > limit)
    }

    /**
     * An add skips ids already on the page, so a replay can't double an item, and a move only
     * moves items still on the page ([ReplaceItems.stillThere]), so it can't bring back an
     * erased one.
     *
     * What's remembered about a page ([idsByPage], [itemsByPage]) is updated along the way. If
     * the change fails partway, the PDF may hold half of it, so its pages are forgotten and
     * read again when next needed. A turned page is forgotten too: turning changes none of its
     * items, but it's rare, and reading again is the surest way to stay right.
     */
    override fun apply(change: Change) {
        try {
            when (change) {
                is AddItems -> onPage(change.page) { p ->
                    val there = idsFor(change.page)
                    add(p, change.page, change.items.filter { it.id !in there })
                }
                is RemoveItems -> onPage(change.page) { p ->
                    remove(p, change.page, change.items)
                    UPDATE_PAGE
                }
                is ReplaceItems -> onPage(change.page) { p ->
                    val move = change.stillThere(idsFor(change.page))
                    remove(p, change.page, move.before)
                    add(p, change.page, move.after)
                }
                is RotatePages -> {
                    change.after.forEach { (page, degrees) -> if (page in 0 until pageCount) setRotation(page, degrees) }
                    change.pages.forEach { forgetPage(it) }
                }
            }
        } catch (e: Throwable) {
            change.pages.forEach { forgetPage(it) }
            throw e
        }
    }

    /**
     * Takes every one of the app's annotations (strokes and snips) off every page, for a fresh
     * attempt, and returns how many went. Other apps' annotations stay, and so do pages: a
     * dotted page the owner added stays, empty.
     *
     * It goes by what marks an annotation as the app's, not by [items]: a stroke whose data no
     * longer decodes isn't among the items, yet it is still the owner's ink. And nothing is
     * decoded, so a page of snips doesn't have each picture made into a PNG just to remove it.
     */
    fun removeOwnItems(): Int {
        var removed = 0
        for (page in 0 until pageCount) {
            // Asking for the ids alone is quick; most pages of a workbook have no writing.
            if (!hasOwnItems(page)) continue
            try {
                onPage(page) { p ->
                    p.annotations.orEmpty().filter { annotations.isOurs(it.getObject()) }.forEach {
                        annotations.forget(it.getObject())
                        p.deleteAnnotation(it)
                        removed++
                    }
                    UPDATE_PAGE
                }
            } finally {
                // Nothing of the app's is left on it (or, after a failure, it's read again).
                forgetPage(page)
            }
        }
        return removed
    }

    /**
     * The page as shown (rotation applied, ink included), cut to [displayBox] in display points
     * (origin top-left, y down), at [pxPerPt] pixels per point. Snips use it as it is, with
     * all ink smoothed. With [smoothInk] false, ink is drawn as [render] draws it: the sharp
     * patch over a zoomed page must look like the page's own picture.
     */
    fun renderRegion(page: Int, displayBox: Box, pxPerPt: Float, smoothInk: Boolean = true): Bitmap {
        val p = doc.loadPage(page) as PDFPage
        try {
            val w = Math.round((displayBox.x1 - displayBox.x0) * pxPerPt).coerceAtLeast(1)
            val h = Math.round((displayBox.y1 - displayBox.y0) * pxPerPt).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // MuPDF's page space is the page as shown, so scaling it gives display pixels. The
            // device's origin says which of those pixels lands at the bitmap's top-left.
            val x0 = Math.round(displayBox.x0 * pxPerPt)
            val y0 = Math.round(displayBox.y0 * pxPerPt)
            val ctm = Matrix.Scale(pxPerPt)
            if (!smoothInk) {
                drawWithSolidInk(p, bmp, RectI(x0, y0, x0 + w, y0 + h), ctm)
                return bmp
            }
            val dev = AndroidDrawDevice(bmp, x0, y0)
            try {
                p.run(dev, ctm, null)
                dev.close()
            } finally {
                dev.destroy()
            }
            return bmp
        } finally {
            p.destroy()
        }
    }

    /**
     * Runs [edit] on a loaded page, then lets MuPDF catch up, unless [edit] answers
     * [LEAVE_PAGE_ALONE] (see [add]). A page that doesn't exist is skipped.
     */
    private fun onPage(page: Int, edit: (PDFPage) -> Boolean) {
        if (page !in 0 until pageCount) return
        val p = doc.loadPage(page) as PDFPage
        try {
            if (edit(p) == UPDATE_PAGE) p.update()
        } finally {
            p.destroy()
        }
    }

    /**
     * Adds [items] at the end of the page, in this order. What's remembered about the page gets
     * each one as the PDF now holds it, read back from its new annotation, not as it was handed
     * in: a stroke's numbers are rounded when written, and a picture is stored in MuPDF's own
     * way. That is what reading the page again would give.
     *
     * Strokes alone, which is nearly every change, are written the quick way (see
     * ItemAnnotations.addInk): MuPDF's own drawing step is left out for each of them, and the
     * answer is then [LEAVE_PAGE_ALONE], because an update of this loaded page would draw
     * MuPDF's plain line over each new stroke. With a picture among them, everything goes the
     * old way and the page is updated as before.
     */
    private fun add(p: PDFPage, page: Int, items: List<Item>): Boolean {
        val strokesOnly = items.isNotEmpty() && items.all { it is InkItem }
        for (item in items) {
            val dict = annotations.add(p, item, pageWontBeUpdated = strokesOnly)
            idsByPage[page]?.let { ids -> annotations.idOf(dict)?.let { ids += it } }
            itemsByPage[page]?.let { cached -> annotations.read(dict)?.let { cached += it } }
        }
        return if (strokesOnly) LEAVE_PAGE_ALONE else UPDATE_PAGE
    }

    /** Deletes the app's annotations with these items' ids. Ids that aren't there are skipped. */
    private fun remove(p: PDFPage, page: Int, items: List<Item>) {
        if (items.isEmpty()) return
        val ids = items.mapTo(HashSet()) { it.id }
        p.annotations.orEmpty()
            .filter { annotations.isOurs(it.getObject()) && annotations.idOf(it.getObject()) in ids }
            .forEach {
                annotations.forget(it.getObject())
                p.deleteAnnotation(it)
            }
        // Every annotation with one of these ids is gone now, so none is left to remember.
        idsByPage[page]?.removeAll(ids)
        itemsByPage[page]?.removeAll { it.id in ids }
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

    /** The ids of the app's items on [page]: read from the PDF once (without decoding any item), then remembered. */
    private fun idsFor(page: Int): Set<String> = idsByPage.getOrPut(page) {
        annotationDicts(page).mapNotNullTo(HashSet()) { d -> if (annotations.isOurs(d)) annotations.idOf(d) else null }
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
        /** Half covered, out of 255: from here a pixel of solid ink is painted in full ([drawSolid]). */
        private const val HALF = 128

        /** How many rows of the picture the solid-ink step handles at a time ([drawSolid]). */
        private const val SOLID_BAND_ROWS = 256

        /**
         * Rows drawn above and below a band and thrown away. MuPDF smooths the rows at the edge of
         * what it's asked to draw a little differently from the same rows inside a bigger
         * picture, so without them a stroke's edge was a pixel off where two bands met.
         */
        private const val SOLID_BAND_MARGIN = 2

        /** What an edit tells [onPage]: whether MuPDF may update the loaded page afterwards. */
        private const val UPDATE_PAGE = true
        private const val LEAVE_PAGE_ALONE = false

        /** How many pages' decoded items are remembered at once (see [itemsByPage]). */
        private const val ITEM_PAGES_KEPT = 4

        /** The private key, in a page's dictionary, that marks a page the app added ([addDottedPage]). */
        private const val ADDED_PAGE_KEY = "PNotesAdded"

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
