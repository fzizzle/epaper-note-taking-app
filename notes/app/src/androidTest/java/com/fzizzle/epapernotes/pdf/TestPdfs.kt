package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.fzizzle.epapernotes.ink.Box
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** Builds small PDF files for the tablet tests, using MuPDF's own API. */
object TestPdfs {

    /**
     * A PDF of plain white, empty pages. [mediaBox] can be offset from 0,0, as some exports
     * are. [parentRotate] puts /Rotate on the page tree instead of on each page, and
     * [parentCropBox] a /CropBox, which every page then inherits.
     */
    fun blank(
        dir: File,
        pages: Int = 2,
        mediaBox: Box = Box(0f, 0f, 612f, 792f),
        parentRotate: Int? = null,
        parentCropBox: Box? = null,
        parentLoop: Boolean = false,
        saveOptions: String = "",
    ): File {
        val doc = PDFDocument()
        repeat(pages) {
            val page = doc.addPage(Rect(0f, 0f, 612f, 792f), 0, doc.newDictionary(), "")
            // Set the box directly: addPage normalises its rect to start at 0,0. Numbers go in
            // with push(Float), never push(PDFObject), which frees what it pushes (see MuPdfStressTest).
            val box = doc.newArray()
            listOf(mediaBox.x0, mediaBox.y0, mediaBox.x1, mediaBox.y1).forEach { box.push(it) }
            page.put("MediaBox", box)
            // addPage always writes /Rotate 0, which would hide a rotation set on the page tree.
            page.delete("Rotate")
            doc.insertPage(-1, page)
        }
        val tree = doc.trailer.get("Root").get("Pages")
        if (parentRotate != null) tree.put("Rotate", parentRotate)
        if (parentCropBox != null) {
            val crop = doc.newArray()
            listOf(parentCropBox.x0, parentCropBox.y0, parentCropBox.x1, parentCropBox.y1).forEach { crop.push(it) }
            tree.put("CropBox", crop)
        }
        // A malformed file whose page tree points back at itself.
        if (parentLoop) tree.put("Parent", tree)
        val file = File.createTempFile("blank", ".pdf", dir)
        doc.save(file.path, saveOptions)
        doc.destroy()
        return file
    }

    /** A valid 3-page PDF cut off partway, like an interrupted download. */
    fun truncated(dir: File): File {
        val whole = blank(dir, pages = 3).readBytes()
        return File(dir, "cut.pdf").apply { writeBytes(whole.copyOf(whole.size * 6 / 10)) }
    }

    /**
     * A valid PDF whose pointer to its object table is wrong, a common kind of damage. MuPDF
     * opens it by rebuilding the table, and reports it as repaired.
     */
    fun brokenXref(dir: File): File {
        val text = String(blank(dir, pages = 2).readBytes(), Charsets.ISO_8859_1)
        val broken = Regex("startxref\\s+\\d+").replace(text, "startxref\n999999")
        return File(dir, "broken.pdf").apply { writeBytes(broken.toByteArray(Charsets.ISO_8859_1)) }
    }

    /** 1 KB of random bytes with a .pdf name, like a damaged download. */
    fun garbage(dir: File): File = File(dir, "bad.pdf").apply { writeBytes(Random(42).nextBytes(1024)) }

    /** A one-page PDF with a highlight, a link and a free-text note, as another app might leave. */
    fun withForeignAnnotations(dir: File): File {
        val source = blank(dir, pages = 1)
        val doc = PDFDocument.openDocument(source.path).asPDF()
        val page = doc.loadPage(0) as PDFPage
        page.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT).apply {
            setQuadPoints(arrayOf(Quad(Rect(100f, 100f, 300f, 120f)))); update()
        }
        page.createLink(Rect(100f, 200f, 300f, 220f), "https://example.com")
        page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT).apply {
            setRect(Rect(100f, 300f, 300f, 340f)); setContents("a note"); update()
        }
        page.update()
        val file = File.createTempFile("foreign", ".pdf", dir)
        doc.save(file.path, "")
        doc.destroy()
        return file
    }

    /** A one-page PDF with a single highlight, as another app might leave. */
    fun withHighlight(dir: File): File {
        val source = blank(dir, pages = 1)
        val doc = PDFDocument.openDocument(source.path).asPDF()
        val page = doc.loadPage(0) as PDFPage
        page.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT).apply {
            setQuadPoints(arrayOf(Quad(Rect(100f, 100f, 300f, 120f)))); update()
        }
        page.update()
        val file = File.createTempFile("highlight", ".pdf", dir)
        doc.save(file.path, "")
        doc.destroy()
        return file
    }

    /** Subtype and /Rect of every annotation on page 0 that the app didn't make, to compare across a save. */
    fun foreignSignature(file: File): List<String> {
        val doc = PDFDocument.openDocument(file.path).asPDF()
        val annots = doc.findPage(0).get("Annots")
        val out = (0 until annots.size()).map { annots.get(it) }
            .filter { it.get("PNotesStroke").isNull && it.get("PNotesImage").isNull }
            .map { "${it.get("Subtype").asName()} ${it.get("Rect")}" }
        doc.destroy()
        return out
    }

    /**
     * A one-page PDF with [count] strokes written the plan-2 way: an ink annotation carrying
     * only /PNotesStroke, with no /NM id.
     */
    fun withPlan2Strokes(dir: File, count: Int): File {
        val source = blank(dir, pages = 1)
        val doc = PDFDocument.openDocument(source.path).asPDF()
        val page = doc.loadPage(0) as PDFPage
        repeat(count) { n ->
            val y = 400f + n * 20
            page.createAnnotation(PDFAnnotation.TYPE_INK).apply {
                setInkList(arrayOf(arrayOf(Point(100f, 792f - y), Point(300f, 792f - y))))
                getObject().put("PNotesStroke", "pn1 pen 100.00,$y,0.500 300.00,$y,0.500")
                getObject().delete("NM")
                update()
            }
        }
        page.update()
        val file = File.createTempFile("plan2", ".pdf", dir)
        doc.save(file.path, "")
        doc.destroy()
        return file
    }

    /** A PNG, red on its left half and blue on its right, to see which way a picture is turned. */
    fun redBluePng(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) bmp.setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun isDark(px: Int) = Color.red(px) < 100 && Color.green(px) < 100 && Color.blue(px) < 100

    /** How many dark pixels column [x] has: a measure of line thickness there. */
    fun darkHeightAt(bmp: Bitmap, x: Int) = (0 until bmp.height).count { isDark(bmp.getPixel(x, it)) }
}
