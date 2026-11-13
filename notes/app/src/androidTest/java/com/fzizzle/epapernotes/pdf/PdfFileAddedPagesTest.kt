package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.util.SizeF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pages of dotted paper the app adds to a PDF, and takes away again. */
@RunWith(AndroidJUnit4::class)
class PdfFileAddedPagesTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val line = Stroke(StrokeKind.PEN, (0..50).map { StrokePoint(100f + it * 4, 200f, it / 50f) })

    private val tall = SizeF(612f, 792f)
    private val wide = SizeF(400f, 300f)

    /** Adds [stroke] as a fresh item and returns its id, the way the session does. */
    private fun PdfFile.add(page: Int, stroke: Stroke): String {
        val id = ItemIds.next()
        apply(AddItems(page, listOf(InkItem(id, stroke))))
        return id
    }

    private fun PdfFile.ids(page: Int) = items(page).map { it.id }

    /** A two-page PDF whose pages differ in size, so each page can be told by its size: [tall], then [wide]. */
    private fun twoSizes(): File {
        val doc = PDFDocument()
        doc.insertPage(-1, doc.addPage(Rect(0f, 0f, tall.width, tall.height), 0, doc.newDictionary(), ""))
        doc.insertPage(-1, doc.addPage(Rect(0f, 0f, wide.width, wide.height), 0, doc.newDictionary(), ""))
        val file = File.createTempFile("two-sizes", ".pdf", dir)
        doc.save(file.path, "")
        doc.destroy()
        return file
    }

    private fun pixels(bmp: Bitmap) = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }

    @Test fun anAddedPageComesRightAfter() {
        PdfFile.open(twoSizes()).use { pdf ->
            val a = pdf.add(0, line)
            val b = pdf.add(1, line)
            // Read both pages first, so what's remembered about them must be dropped.
            assertEquals(listOf(a), pdf.ids(0))
            assertEquals(listOf(b), pdf.ids(1))

            pdf.addDottedPage(0)

            assertEquals(3, pdf.pageCount)
            assertFalse(pdf.isAddedPage(0))
            assertTrue(pdf.isAddedPage(1))
            assertFalse(pdf.isAddedPage(2))
            assertEquals(tall, pdf.displaySize(0))
            assertEquals(tall, pdf.displaySize(1))
            assertEquals(wide, pdf.displaySize(2))
            assertEquals(listOf(a), pdf.ids(0))
            assertEquals(emptyList<String>(), pdf.ids(1))
            assertFalse(pdf.hasOwnItems(1))
            assertEquals(listOf(b), pdf.ids(2))
            assertTrue(pdf.hasOwnItems(2))

            // After the last page too.
            pdf.addDottedPage(2)
            assertEquals(4, pdf.pageCount)
            assertTrue(pdf.isAddedPage(3))
            assertEquals(wide, pdf.displaySize(3))
            assertEquals(listOf(b), pdf.ids(2))
        }
    }

    @Test fun anAddedPageMatchesARotatedNeighbour() {
        PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
            pdf.setRotation(0, 90)
            pdf.addDottedPage(0)
            assertEquals(SizeF(792f, 612f), pdf.displaySize(0))
            assertEquals(pdf.displaySize(0), pdf.displaySize(1))
            assertEquals(0, pdf.rotation(1))
        }
    }

    @Test fun anAddedPageIsDottedAndTakesInk() {
        val out = File(dir, "dotted-ink.pdf")
        val id = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
            pdf.addDottedPage(0)
            val blank = pixels(pdf.render(0, 600))
            assertEquals("the old page stays white", 0, blank.count { Color.red(it) < 250 })
            val dotted = pixels(pdf.render(1, 600))
            val grey = dotted.count { Color.red(it) in 40..230 }
            val white = dotted.count { Color.red(it) > 250 }
            assertTrue("grey dots: $grey", grey > 1000)
            assertTrue("mostly white: $white of ${dotted.size}", white > dotted.size * 9 / 10)
            assertEquals("no black", 0, dotted.count { Color.red(it) < 40 })
            pdf.add(1, line).also { pdf.saveTo(out) }
        }
        PdfFile.open(out).use { pdf ->
            assertTrue(pdf.isAddedPage(1))
            assertEquals(line, (pdf.items(1).single() as InkItem).stroke)
            assertEquals(id, pdf.items(1).single().id)
        }
    }

    @Test fun onlyAddedPagesCanBeRemoved() {
        PdfFile.open(twoSizes()).use { pdf ->
            val a = pdf.add(0, line)
            val b = pdf.add(1, line)
            pdf.addDottedPage(0)
            pdf.add(1, line) // ink on the added page goes with it
            assertEquals(listOf(b), pdf.ids(2))

            assertThrows(IllegalArgumentException::class.java) { pdf.removeAddedPage(0) }
            assertThrows(IllegalArgumentException::class.java) { pdf.removeAddedPage(2) }
            assertEquals(3, pdf.pageCount)

            pdf.removeAddedPage(1)
            assertEquals(2, pdf.pageCount)
            assertEquals(tall, pdf.displaySize(0))
            assertEquals(wide, pdf.displaySize(1))
            assertEquals(listOf(a), pdf.ids(0))
            assertEquals(listOf(b), pdf.ids(1))
            assertFalse(pdf.isAddedPage(0))
            assertFalse(pdf.isAddedPage(1))
        }
    }

    /** A document is never left with no pages, even when its only page carries the key. */
    @Test fun theOnlyPageIsNeverRemoved() {
        val doc = PDFDocument.openDocument(TestPdfs.blank(dir, pages = 1).path).asPDF()
        doc.findPage(0).put("PNotesAdded", true)
        val file = File.createTempFile("only-added", ".pdf", dir)
        doc.save(file.path, "")
        doc.destroy()
        PdfFile.open(file).use { pdf ->
            assertTrue(pdf.isAddedPage(0))
            assertThrows(IllegalArgumentException::class.java) { pdf.removeAddedPage(0) }
            assertEquals(1, pdf.pageCount)
        }
    }

    @Test fun theKeySurvivesSaveAndReopen() {
        val out = File(dir, "added-key.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.addDottedPage(1); it.saveTo(out) }
        PdfFile.open(out).use { pdf ->
            assertEquals(3, pdf.pageCount)
            assertFalse(pdf.isAddedPage(0))
            assertFalse(pdf.isAddedPage(1))
            assertTrue(pdf.isAddedPage(2))
            pdf.removeAddedPage(2)
            assertEquals(2, pdf.pageCount)
        }
    }

    /**
     * Adds a page after page 0 of [file] and checks it is the neighbour's size as shown, dotted
     * all over (not only in a corner, as a page clipped by a box it inherits would be), and
     * that a stroke on it reads back after a save.
     */
    private fun addsAMatchingPageAfter(file: File) {
        val out = File(dir, "matching-${file.name}")
        val id = PdfFile.open(file).use { pdf ->
            pdf.addDottedPage(0)
            assertEquals(pdf.displaySize(0), pdf.displaySize(1))
            val bmp = pdf.render(1, 600)
            val px = pixels(bmp)
            fun greyIn(left: Boolean, top: Boolean): Int {
                val xs = if (left) 0 until bmp.width / 2 else bmp.width / 2 until bmp.width
                val ys = if (top) 0 until bmp.height / 2 else bmp.height / 2 until bmp.height
                return ys.sumOf { y -> xs.count { x -> Color.red(px[y * bmp.width + x]) in 40..230 } }
            }
            for (left in listOf(true, false)) for (top in listOf(true, false)) {
                val grey = greyIn(left, top)
                assertTrue("grey dots in the ${if (top) "top" else "bottom"} ${if (left) "left" else "right"} quarter: $grey", grey > 100)
            }
            val short = Stroke(StrokeKind.PEN, (0..10).map { StrokePoint(50f + it * 4, 60f, 0.5f) })
            pdf.add(1, short).also { pdf.saveTo(out) }
        }
        PdfFile.open(out).use { pdf ->
            assertTrue(pdf.isAddedPage(1))
            assertEquals(listOf(id), pdf.ids(1))
        }
    }

    @Test fun anAddedPageMatchesARotationFromThePageTree() {
        val file = TestPdfs.blank(dir, parentRotate = 90)
        PdfFile.open(file).use { assertEquals(SizeF(792f, 612f), it.displaySize(0)) }
        addsAMatchingPageAfter(file)
    }

    @Test fun anAddedPageMatchesAMediaBoxAwayFromTheCorner() {
        val file = TestPdfs.blank(dir, mediaBox = Box(50f, 80f, 650f, 880f))
        PdfFile.open(file).use { assertEquals(SizeF(600f, 800f), it.displaySize(0)) }
        addsAMatchingPageAfter(file)
    }

    /** A CropBox on the page tree would be inherited by the new page too, and clip it, unless the page has its own. */
    @Test fun anAddedPageIsNotClippedByACropBoxFromThePageTree() {
        val file = TestPdfs.blank(dir, parentCropBox = Box(100f, 150f, 400f, 550f))
        PdfFile.open(file).use { assertEquals(SizeF(300f, 400f), it.displaySize(0)) }
        addsAMatchingPageAfter(file)
    }
}
