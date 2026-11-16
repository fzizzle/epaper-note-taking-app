package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What MuPDF does when the file under an open document is changed by something else: an
 * overwrite in place (a USB copy) or a replacement by rename (Syncthing). The first two tests
 * log what happens (tag "ChangedUnderneath"). Before [PdfFile.open] read files into memory,
 * the first two showed the fault: a copy of 3 pages that opened clean, and a blank page 30.
 * All three now assert the document stays A, whole (see the findings log, 2026-11-16).
 */
@RunWith(AndroidJUnit4::class)
class PdfFileChangedUnderneathTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val line = Stroke(StrokeKind.PEN, (0..50).map { StrokePoint(100f + it * 4, 200f, it / 50f) })

    /** [pages] pages, each with a note saying which page it is, so pages can be told apart. */
    private fun withText(pages: Int, name: String): File {
        val doc = PDFDocument()
        repeat(pages) { n ->
            val page = doc.addPage(Rect(0f, 0f, 612f, 792f), 0, doc.newDictionary(), "")
            doc.insertPage(-1, page)
            val p = doc.loadPage(n) as PDFPage
            p.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT).apply {
                setRect(Rect(100f, 100f + n * 5f, 400f, 160f + n * 5f)); setContents("page number $n of $name text ".repeat(40)); update()
            }
            p.update()
        }
        val file = File.createTempFile(name, ".pdf", dir)
        doc.save(file.path, "")
        doc.destroy()
        return file
    }

    private fun freshA(): File {
        val a = withText(40, "deckA")
        return File.createTempFile("copyOfDeckA", ".pdf", dir).also { a.copyTo(it, overwrite = true) }
    }

    private fun PdfFile.stroke() {
        apply(AddItems(0, listOf(InkItem(ItemIds.next(), line))))
    }

    private fun say(msg: String) {
        Log.i("ChangedUnderneath", msg)
    }

    private fun overwriteInPlace(target: File, with: File) {
        FileOutputStream(target).use { it.write(with.readBytes()) } // truncates to B's length
    }

    private fun blank0(b: Bitmap) = b.getPixel(0, 0)

    private fun hash(b: Bitmap): Int {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return px.contentHashCode()
    }

    @Test fun overwrittenInPlaceThenSaved() {
        val a = freshA()
        val b = withText(3, "deckB")
        val copy = File(dir, "copy-inplace-saved.pdf").also { it.delete() }
        PdfFile.open(a).use { pdf ->
            pdf.render(0, 600).recycle()
            val before = a.length()
            overwriteInPlace(a, b)
            say("inplace-saved: A was $before bytes, is ${a.length()} now (B is ${b.length()})")
            try {
                pdf.stroke()
                pdf.saveTo(copy)
            } catch (e: Throwable) {
                say("inplace-saved: threw $e")
                throw e
            }
        }
        say("inplace-saved: save did not throw, copy ${copy.length()} bytes")
        PdfFile.open(copy).use { c ->
            say("inplace-saved: copy opens, wasRepaired=${c.wasRepaired} pages=${c.pageCount}")
            assertFalse(c.wasRepaired)
            assertEquals(40, c.pageCount)
            c.render(30, 600).recycle()
        }
    }

    @Test fun overwrittenInPlaceThenDrawn() {
        val a = freshA()
        val b = withText(3, "deckB")
        val expected = PdfFile.open(a).use { p -> hash(p.render(30, 600)) }
        PdfFile.open(a).use { pdf ->
            pdf.render(0, 600).recycle()
            overwriteInPlace(a, b)
            try {
                val got = pdf.render(30, 600)
                val blank = IntArray(got.width * got.height).also { got.getPixels(it, 0, got.width, 0, 0, got.width, got.height) }.all { it == blank0(got) }
                val kind = if (hash(got) == expected) "A's page 30" else "something else (blank=$blank, pages=${pdf.pageCount})"
                say("inplace-drawn: render(30) gave $kind")
                assertEquals(expected, hash(got))
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                say("inplace-drawn: threw $e")
                throw e
            }
        }
    }

    @Test fun replacedByRenameThenSaved() {
        val a = freshA()
        val b = withText(3, "deckB")
        val beside = File(dir, "beside-${a.name}").also { it.delete() }
        b.copyTo(beside)
        val copy = File(dir, "copy-rename-saved.pdf").also { it.delete() }
        PdfFile.open(a).use { pdf ->
            pdf.render(0, 600).recycle()
            Files.move(beside.toPath(), a.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            pdf.stroke()
            pdf.saveTo(copy)
        }
        PdfFile.open(copy).use { c ->
            say("rename-saved: copy wasRepaired=${c.wasRepaired} pages=${c.pageCount}")
            assertFalse(c.wasRepaired)
            assertEquals(40, c.pageCount)
            assertEquals(1, c.items(0).size)
            c.render(30, 600).recycle()
        }
    }
}
