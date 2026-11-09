package com.fzizzle.epapernotes.pdf

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFDocument
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.doc.RemoveItems
import com.fzizzle.epapernotes.doc.ReplaceItems
import com.fzizzle.epapernotes.doc.RotatePages
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemCodec
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.pdf.TestPdfs.isDark
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfFileItemsTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val line = Stroke(StrokeKind.PEN, (0..50).map { StrokePoint(100f + it * 4, 400f, 0.8f) })
    private fun ink(id: String, stroke: Stroke = line) = InkItem(id, stroke)
    private fun dot(x: Float, y: Float) = Stroke(StrokeKind.PEN, listOf(StrokePoint(x, y, 1f), StrokePoint(x, y + 1f, 1f)))
    private fun picture(id: String, box: Box = Box(200f, 200f, 300f, 300f), turns: Int = 0) =
        ImageItem(id, box, turns, TestPdfs.redBluePng(40, 20))

    @Test fun itemsHaveTheirIdsAfterSave() {
        val out = File(dir, "items.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use {
            it.apply(AddItems(0, listOf(ink("aaa"), picture("bbb"))))
            it.saveTo(out)
        }
        PdfFile.open(out).use { assertEquals(listOf("aaa", "bbb"), it.items(0).map { i -> i.id }) }
    }

    @Test fun removeDeletesOnlyThatItem() = PdfFile.open(TestPdfs.blank(dir)).use {
        it.apply(AddItems(0, listOf(ink("a"), ink("b"), picture("c"))))
        it.apply(RemoveItems(0, listOf(ink("a"))))
        assertEquals(listOf("b", "c"), it.items(0).map { i -> i.id })
        it.apply(RemoveItems(0, listOf(picture("c"))))
        assertEquals(listOf("b"), it.items(0).map { i -> i.id })
    }

    @Test fun removeOfUnknownIdIsSkipped() = PdfFile.open(TestPdfs.blank(dir)).use {
        it.apply(AddItems(0, listOf(ink("a"))))
        it.apply(RemoveItems(0, listOf(ink("nope"), picture("gone"))))
        assertEquals(listOf("a"), it.items(0).map { i -> i.id })
    }

    @Test fun replaceMovesInk() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        // PDF (300, 400) is display (300, 392) at 1 px per point; 100 pt to the right is (400, 392).
        val before = ink("m", dot(300f, 400f))
        val after = ink("m", dot(400f, 400f))
        pdf.apply(AddItems(0, listOf(before)))
        assertTrue(isDark(pdf.render(0, 612).getPixel(300, 392)))
        pdf.apply(ReplaceItems(0, listOf(before), listOf(after)))
        val bmp = pdf.render(0, 612)
        assertTrue(isDark(bmp.getPixel(400, 392)))
        assertFalse(isDark(bmp.getPixel(300, 392)))
        assertEquals(listOf(after), pdf.items(0))
    }

    /** Ruling R1: a snip from a page at /Rotate 90 has quarterTurns 1, and looks upright there. Likewise for each turn. */
    @Test fun snipIsUprightOnRotatedPage() = (0..3).forEach { turns ->
        PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
            pdf.setRotation(0, turns * 90)
            val box = Box(200f, 300f, 300f, 500f) // 100 pt wide and 200 tall in PDF space
            pdf.apply(AddItems(0, listOf(picture("s", box, turns))))
            val bmp = pdf.render(0, pdf.displaySize(0).width.toInt()) // 1 px per point
            val centre = pdf.pdfToDisplay(0).map(Pt(250f, 400f))
            val left = bmp.getPixel(centre.x.toInt() - 25, centre.y.toInt())
            val right = bmp.getPixel(centre.x.toInt() + 25, centre.y.toInt())
            assertTrue("turns $turns left ${Integer.toHexString(left)}", isRed(left))
            assertTrue("turns $turns right ${Integer.toHexString(right)}", isBlue(right))
        }
    }

    @Test fun imageRoundTripsItsPicture() {
        val out = File(dir, "img.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.apply(AddItems(0, listOf(picture("p")))); it.saveTo(out) }
        PdfFile.open(out).use {
            val item = it.items(0).single() as ImageItem
            val back = BitmapFactory.decodeByteArray(item.png, 0, item.png.size)
            assertEquals(40, back.width)
            assertEquals(20, back.height)
            assertTrue(isRed(back.getPixel(5, 10)) && isBlue(back.getPixel(35, 10)))
            assertEquals(0, item.quarterTurns)
            assertEquals(200f, item.box.x0, 0.01f); assertEquals(300f, item.box.y1, 0.01f)
        }
    }

    @Test fun plan2StrokesCanBeErasedAndMoved() {
        val source = TestPdfs.withPlan2Strokes(dir, count = 2)
        PdfFile.open(source).use { pdf ->
            val old = pdf.items(0)
            assertEquals(2, old.size)
            assertTrue(old.all { it.id.matches(Regex("o\\d+")) })
            pdf.apply(RemoveItems(0, listOf(old[0])))
            assertEquals(listOf(old[1].id), pdf.items(0).map { it.id })
            val moved = InkItem("fresh", (old[1] as InkItem).stroke.copy(points = line.points))
            pdf.apply(ReplaceItems(0, listOf(old[1]), listOf(moved)))
            assertEquals(listOf(moved), pdf.items(0))
        }
    }

    /** Review: a move of an item that was erased meanwhile adds nothing; the rest of the move still happens. */
    @Test fun replaceOfMissingItemAddsNothing() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.apply(AddItems(0, listOf(ink("a"), ink("b"))))
        pdf.apply(RemoveItems(0, listOf(ink("a"))))
        pdf.apply(ReplaceItems(0, listOf(ink("a"), ink("b")), listOf(ink("a", dot(50f, 50f)), ink("c", dot(60f, 60f)))))
        assertEquals(listOf(ink("c", dot(60f, 60f))), pdf.items(0))
    }

    @Test fun addOfExistingIdIsSkipped() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.apply(AddItems(0, listOf(ink("a"))))
        pdf.apply(AddItems(0, listOf(ink("a"), picture("p"))))
        assertEquals(listOf("a", "p"), pdf.items(0).map { it.id })
    }

    /** An /NM that isn't a plain id (another app's, say) is ignored: the item goes by its object number. */
    @Test fun oddNamesFallBackToObjectNumbers() {
        val named = File(dir, "odd-names.pdf")
        val doc = PDFDocument.openDocument(TestPdfs.withPlan2Strokes(dir, count = 2).path).asPDF()
        val annots = doc.findPage(0).get("Annots")
        annots.get(0).put("NM", "Not plain | id")
        annots.get(1).put("NM", "abc123")
        doc.save(named.path, "")
        doc.destroy()
        PdfFile.open(named).use { pdf ->
            val ids = pdf.items(0).map { it.id }
            assertTrue(ids[0], ids[0].matches(Regex("o[1-9]\\d*")))
            assertEquals("abc123", ids[1])
            ids.forEach { ItemCodec.encode(InkItem(it, line)) } // both can go in the journal
            pdf.apply(RemoveItems(0, pdf.items(0).take(1)))
            assertEquals(listOf("abc123"), pdf.items(0).map { it.id })
        }
    }

    @Test fun foreignAnnotationsAreNeverTouched() {
        val source = TestPdfs.withForeignAnnotations(dir)
        val before = TestPdfs.foreignSignature(source)
        val out = File(dir, "foreign-items.pdf")
        PdfFile.open(source).use {
            it.apply(AddItems(0, listOf(ink("a"), picture("b"))))
            val items = it.items(0)
            assertEquals(listOf("a", "b"), items.map { i -> i.id })
            it.apply(RemoveItems(0, items))
            it.saveTo(out)
        }
        PdfFile.open(out).use {
            assertTrue(it.items(0).isEmpty())
            assertEquals(3, it.foreignAnnotationCount(0))
        }
        assertEquals(before, TestPdfs.foreignSignature(out))
    }

    @Test fun rotateIsSavedAndUndone() {
        val out = File(dir, "rotate-undo.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use {
            val turn = RotatePages(mapOf(0 to 0), mapOf(0 to 90))
            it.apply(turn)
            assertEquals(90, it.rotation(0))
            it.apply(turn.inverse())
            it.saveTo(out)
        }
        PdfFile.open(out).use { assertEquals(0, it.rotation(0)) }
    }

    @Test fun renderRegionMatchesThePage() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.apply(AddItems(0, listOf(ink("d", dot(300f, 392f))))) // display (300, 400)
        val full = pdf.render(0, 612) // 1 px per point
        val region = pdf.renderRegion(0, Box(200f, 300f, 400f, 500f), 2f)
        assertEquals(400, region.width)
        assertEquals(400, region.height)
        val a = full.getPixel(300, 400)
        val b = region.getPixel(200, 200)
        assertTrue(isDark(a))
        assertEquals(isDark(a), isDark(b))
        assertFalse(isDark(region.getPixel(10, 10))) // the page is white away from the ink
    }

    private fun isRed(px: Int) = Color.red(px) > 180 && Color.green(px) < 80 && Color.blue(px) < 80
    private fun isBlue(px: Int) = Color.blue(px) > 180 && Color.red(px) < 80 && Color.green(px) < 80
}
