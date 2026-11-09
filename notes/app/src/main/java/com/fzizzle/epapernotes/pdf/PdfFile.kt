package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.util.SizeF
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.Closeable
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
class PdfFile private constructor(private val doc: PDFDocument) : Closeable {

    val pageCount: Int get() = doc.countPages()

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
     */
    fun rotation(page: Int): Int {
        var node: PDFObject? = doc.findPage(page)
        while (node != null && !node.isNull) {
            val r = node.get("Rotate")
            if (r.isNumber) return normalise(r.asInteger())
            node = node.get("Parent")
        }
        return 0
    }

    /** Rotates the page. It's written on the page itself, which overrides any inherited value. */
    fun setRotation(page: Int, degrees: Int) {
        doc.findPage(page).put("Rotate", normalise(degrees))
    }

    /** Writes the whole document, with all changes, to [target]. */
    fun saveTo(target: File) {
        doc.save(target.path, "")
    }

    override fun close() = doc.destroy()

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
            return PdfFile(pdf)
        }
    }
}
