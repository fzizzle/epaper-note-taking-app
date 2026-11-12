package com.fzizzle.epapernotes.pdf

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Blank file -> open -> add one item -> save -> reopen -> read it back, many rounds in a row,
 * with the garbage collector forced every few rounds so MuPDF's Java wrappers get finalized
 * while the work goes on.
 *
 * It guards against MuPDF reference-count bugs. `PDFObject.push(PDFObject)` in fitz 1.28.5
 * drops a reference it never took, so the pushed object is freed when its Java wrapper is
 * finalized, while the array still points at it. That corrupted the heap here: crashes in
 * unrelated threads, blank files with "no readable pages", and files read back with no strokes.
 *
 * Arguments: `-e loops N` (default 300), `-e gcEvery N` (default 5, 0 = never force).
 */
@RunWith(AndroidJUnit4::class)
class MuPdfStressTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "stress").apply {
        deleteRecursively(); mkdirs()
    }
    private val line = Stroke(StrokeKind.PEN, (0..50).map { StrokePoint(100f + it * 4, 400f, 0.8f) })
    private val png = TestPdfs.redBluePng(40, 20)
    private val box = Box(200f, 200f, 300f, 300f)
    private val args = InstrumentationRegistry.getArguments()
    private val loops = (args.getString("loops") ?: "300").toInt()
    private val gcEvery = (args.getString("gcEvery") ?: "5").toInt()

    /** One stroke per round: the writing path. */
    @Test fun inkRoundTrips() = roundTrips({ InkItem("s$it", line) }) { it }

    /** One pasted picture per round: the snip path, whose stamp writes its own /Rect and /BBox arrays. */
    @Test fun imageRoundTrips() = roundTrips({ ImageItem("p$it", box, 0, png) }) { (it as ImageItem).let { i -> i.id to i.box } }

    /**
     * The snip path on its own: the stamp's arrays must still hold its box after the wrappers
     * used to build them are finalized and their memory is handed out again, here to a flood
     * of new numbers in another document.
     */
    @Test fun imageBoxSurvivesFinalizedWrappers() {
        repeat(20) { round ->
            val out = File(dir, "image-$round.pdf")
            PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
                pdf.apply(AddItems(0, listOf(ImageItem("p", box, 0, png))))
                System.gc(); System.runFinalization()
                val other = com.artifex.mupdf.fitz.PDFDocument()
                val junk = other.newArray()
                repeat(5000) { junk.push(7777f) }
                pdf.saveTo(out)
                other.destroy()
            }
            val back = PdfFile.open(out).use { it.items(0) }
            assertEquals("round $round", listOf("p" to box), back.map { (it as ImageItem).id to it.box })
        }
    }

    /**
     * The page calls that adding and removing pages will rest on: addPage, insertPage, loadPage
     * and deletePage, 200 rounds in a row with the garbage collector forced every 20 rounds.
     * Page 0 carries a stroke, and it must read back exactly as it was.
     */
    @Test fun addingAndRemovingPagesIsSafe() {
        val source = File(dir, "pages-source.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.apply(AddItems(0, listOf(InkItem("keep", line)))); it.saveTo(source) }
        val before = PdfFile.open(source).use { it.items(0) }

        val doc = PDFDocument.openDocument(source.path).asPDF()
        val mediabox = Rect(0f, 0f, 612f, 792f)
        var expectedPages = doc.countPages()
        for (round in 0 until 200) {
            val content = Buffer().apply { writeLine("0.5 g 10 10 50 50 re f") }
            val page = doc.addPage(mediabox, 0, doc.newDictionary(), content)
            doc.insertPage(1, page)
            expectedPages++
            val loaded = doc.loadPage(1)
            val pix = loaded.toPixmap(Matrix(300f / 612f), ColorSpace.DeviceRGB, false, true)
            assertEquals("round $round width", 300, pix.width)
            pix.destroy(); loaded.destroy()
            if (round % 10 == 9) { doc.deletePage(1); expectedPages-- }
            assertEquals("round $round page count", expectedPages, doc.countPages())
            if (round % 20 == 19) { Runtime.getRuntime().gc(); System.runFinalization() }
        }
        val out = File(dir, "pages-out.pdf")
        doc.save(out.path, "")
        doc.destroy()

        PdfFile.open(out).use {
            assertEquals(expectedPages, it.pageCount)
            assertEquals(before, it.items(0))
        }
    }

    /** [key] picks what must survive the round trip (a picture's PNG is re-encoded, so not its bytes). */
    private fun roundTrips(item: (Int) -> Item, key: (Item) -> Any) {
        for (i in 0 until loops) {
            val blank = TestPdfs.blank(dir)
            val out = File(dir, "out-$i.pdf")
            val added = item(i)
            PdfFile.open(blank).use {
                it.apply(AddItems(0, listOf(added)))
                it.saveTo(out)
            }
            val back = PdfFile.open(out).use { it.items(0) }
            if (i % 50 == 0) Log.i(TAG, "round $i native=${Debug.getNativeHeapAllocatedSize() / 1024} KB")
            assertEquals("round $i (${out.path})", listOf(key(added)), back.map(key))
            if (gcEvery > 0 && i % gcEvery == 0) { System.gc(); System.runFinalization() }
            blank.delete()
            out.delete()
        }
    }

    companion object { const val TAG = "MuPdfStress" }
}
