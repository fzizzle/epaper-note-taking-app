package com.fzizzle.epapernotes.pdf

import com.artifex.mupdf.fitz.PDFDocument
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
    fun blank(dir: File, pages: Int = 2, mediaBox: Box = Box(0f, 0f, 612f, 792f), parentRotate: Int? = null): File {
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
        if (parentRotate != null) {
            doc.trailer.get("Root").get("Pages").put("Rotate", parentRotate)
        }
        val file = File.createTempFile("blank", ".pdf", dir)
        doc.save(file.path, "")
        doc.destroy()
        return file
    }

    /** 1 KB of random bytes with a .pdf name, like a damaged download. */
    fun garbage(dir: File): File = File(dir, "bad.pdf").apply { writeBytes(Random(42).nextBytes(1024)) }
}
