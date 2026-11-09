package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.fzizzle.epapernotes.ink.Box
import java.io.File
import kotlin.random.Random

/** Builds small PDF files for the tablet tests, using MuPDF's own API. */
object TestPdfs {

    /**
     * A PDF of plain white, empty pages. [mediaBox] can be offset from 0,0, as some exports
     * are. [parentRotate] puts /Rotate on the page tree instead of on each page.
     */
    fun blank(
        dir: File,
        pages: Int = 2,
        mediaBox: Box = Box(0f, 0f, 612f, 792f),
        parentRotate: Int? = null,
        parentLoop: Boolean = false,
        saveOptions: String = "",
    ): File {
        val doc = PDFDocument()
        repeat(pages) {
            val page = doc.addPage(Rect(0f, 0f, 612f, 792f), 0, doc.newDictionary(), "")
            // Set the box directly: addPage normalises its rect to start at 0,0.
            val box = doc.newArray()
            listOf(mediaBox.x0, mediaBox.y0, mediaBox.x1, mediaBox.y1).forEach { box.push(doc.newReal(it)) }
            page.put("MediaBox", box)
            // addPage always writes /Rotate 0, which would hide a rotation set on the page tree.
            page.delete("Rotate")
            doc.insertPage(-1, page)
        }
        val tree = doc.trailer.get("Root").get("Pages")
        if (parentRotate != null) tree.put("Rotate", parentRotate)
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

    /** Subtype and /Rect of every annotation on page 0 that the app didn't make, to compare across a save. */
    fun foreignSignature(file: File): List<String> {
        val doc = PDFDocument.openDocument(file.path).asPDF()
        val annots = doc.findPage(0).get("Annots")
        val out = (0 until annots.size()).map { annots.get(it) }
            .filter { it.get("PNotesStroke").isNull }
            .map { "${it.get("Subtype").asName()} ${it.get("Rect")}" }
        doc.destroy()
        return out
    }

    fun isDark(px: Int) = Color.red(px) < 100 && Color.green(px) < 100 && Color.blue(px) < 100

    /** How many dark pixels column [x] has: a measure of line thickness there. */
    fun darkHeightAt(bmp: Bitmap, x: Int) = (0 until bmp.height).count { isDark(bmp.getPixel(x, it)) }
}
