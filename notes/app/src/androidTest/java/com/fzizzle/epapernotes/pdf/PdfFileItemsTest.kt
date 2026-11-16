package com.fzizzle.epapernotes.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.PDFDocument
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.doc.RemoveItems
import com.fzizzle.epapernotes.doc.ReplaceItems
import com.fzizzle.epapernotes.doc.RotatePages
import com.fzizzle.epapernotes.geometry.Affine
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemCodec
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.ink.transformed
import com.fzizzle.epapernotes.pdf.TestPdfs.isDark
import java.io.ByteArrayOutputStream
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

    /** Undo holds on to plan-2 ids (`o<n>`), so neither a save nor reopening the saved file may renumber them. */
    @Test fun plan2IdsStayTheSameAcrossSave() {
        val out = File(dir, "plan2-ids.pdf")
        val kept = PdfFile.open(TestPdfs.withPlan2Strokes(dir, count = 3)).use { pdf ->
            val old = pdf.items(0)
            assertEquals(3, old.size)
            assertTrue(old.all { it.id.matches(Regex("o\\d+")) })
            pdf.apply(RemoveItems(0, listOf(old[1])))
            val left = pdf.items(0).map { it.id }
            assertEquals(listOf(old[0].id, old[2].id), left)
            pdf.saveTo(out)
            assertEquals(left, pdf.items(0).map { it.id }) // in memory, after the save
            left
        }
        PdfFile.open(out).use { assertEquals(kept, it.items(0).map { i -> i.id }) }
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

    /**
     * A crash replay adds what the saved file may already hold. The ids on a page are
     * remembered rather than read each time, so: both for ids read from the file and for ids
     * added, removed and added again since.
     */
    @Test fun replayingAnAddTwiceAddsItOnce() {
        val out = File(dir, "replay-add.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.apply(AddItems(0, listOf(ink("a"), ink("b")))); it.saveTo(out) }
        PdfFile.open(out).use { pdf ->
            val replay = AddItems(0, listOf(ink("b"), ink("c")))
            pdf.apply(replay)
            pdf.apply(replay)
            assertEquals(listOf("a", "b", "c"), pdf.items(0).map { it.id })
            pdf.apply(RemoveItems(0, listOf(ink("b"))))
            pdf.apply(replay) // b is gone, so this time it is added; c still isn't
            pdf.apply(replay)
            assertEquals(listOf("a", "c", "b"), pdf.items(0).map { it.id })
            assertEquals(listOf("a", "c", "b"), pdf.readItems(0).map { it.id })
        }
    }

    /** The undo of a move, after the moved item was erased: with the page's ids remembered, too. */
    @Test fun aMoveOfAnErasedItemBringsNothingBack() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        val before = ink("m", dot(300f, 400f))
        val after = ink("m", dot(400f, 400f))
        pdf.apply(AddItems(0, listOf(ink("a"), before)))
        val move = ReplaceItems(0, listOf(before), listOf(after))
        pdf.apply(move)
        pdf.apply(RemoveItems(0, listOf(after)))
        pdf.apply(move.inverse())
        pdf.apply(move)
        assertEquals(listOf("a"), pdf.items(0).map { it.id })
        assertEquals(listOf("a"), pdf.readItems(0).map { it.id })
    }

    /**
     * Review 4.3: a page's items are remembered between an erase and the next, not decoded
     * again each time. What's remembered must be what reading the PDF again gives: the same
     * items, in the same order, after every kind of change, and after the file is saved and
     * opened again.
     */
    @Test fun cachedItemsMatchAFreshRead() {
        val out = File(dir, "cached-items.pdf")
        // Numbers that don't survive being written as text unchanged, as real pen points don't.
        fun wobbly(id: String, y: Float) = ink(id, Stroke(StrokeKind.PEN, (0..20).map { StrokePoint(100.1234f + it * 3.3333f, y + it / 7f, 0.123456f + it / 40f) }))
        fun check(pdf: PdfFile, ids: List<String>) {
            val cached = pdf.items(0)
            assertEquals(ids, cached.map { it.id })
            assertEquals(pdf.readItems(0), cached) // exactly: ids, order, every point, every picture
        }
        PdfFile.open(TestPdfs.blank(dir, pages = 7)).use { pdf ->
            val a = wobbly("a", 100f); val b = wobbly("b", 150f); val c = wobbly("c", 200f)
            val snip = picture("p", Box(200.123f, 300.456f, 300.789f, 350.5f))
            pdf.apply(AddItems(0, listOf(a, b, c, snip)))
            check(pdf, listOf("a", "b", "c", "p")) // from here on the page is remembered

            pdf.apply(AddItems(0, listOf(wobbly("d", 250f))))
            check(pdf, listOf("a", "b", "c", "p", "d"))
            pdf.apply(RemoveItems(0, listOf(b)))
            check(pdf, listOf("a", "c", "p", "d"))
            // Moves go by what the page holds, as the lasso's do.
            val heldC = pdf.items(0).first { it.id == "c" }
            pdf.apply(ReplaceItems(0, listOf(heldC), listOf(heldC.transformed(Affine.translate(12.345f, -6.789f), 1f))))
            check(pdf, listOf("a", "p", "d", "c"))
            val heldSnip = pdf.items(0).first { it.id == "p" } as ImageItem
            pdf.apply(ReplaceItems(0, listOf(heldSnip), listOf(heldSnip.copy(box = Box(50.5f, 60.25f, 180.125f, 140.75f)))))
            check(pdf, listOf("a", "d", "c", "p"))
            pdf.apply(AddItems(0, listOf(picture("q", turns = 1), wobbly("e", 300f)))) // a second snip, and ink after it
            check(pdf, listOf("a", "d", "c", "p", "q", "e"))
            pdf.apply(AddItems(0, listOf(wobbly("d", 250f), wobbly("f", 350f)))) // a replay: d is there already
            check(pdf, listOf("a", "d", "c", "p", "q", "e", "f"))
            pdf.apply(ReplaceItems(0, listOf(b), listOf(wobbly("b", 500f)))) // a move of an erased item
            check(pdf, listOf("a", "d", "c", "p", "q", "e", "f"))

            pdf.apply(RotatePages(mapOf(0 to 0), mapOf(0 to 90)))
            check(pdf, listOf("a", "d", "c", "p", "q", "e", "f"))
            pdf.apply(AddItems(0, listOf(wobbly("g", 400f))))
            pdf.apply(RemoveItems(0, listOf(a)))
            val ids = listOf("d", "c", "p", "q", "e", "f", "g")
            check(pdf, ids)

            // Other pages, more than are remembered at once, each written on after it was read.
            for (page in 1..6) {
                assertTrue(pdf.items(page).isEmpty())
                pdf.apply(AddItems(page, listOf(wobbly("s$page", 100f))))
            }
            for (page in 1..6) assertEquals(pdf.readItems(page), pdf.items(page))
            check(pdf, ids)

            pdf.saveTo(out)
            check(pdf, ids) // a save changes nothing about what's on the page

            // The same file, opened again. Strokes come back exactly. A picture's box is
            // written to the file as rounded text, and its PNG is made again by MuPDF from the
            // stored image, so those are compared as far as they can be: the box closely, the
            // picture by its size.
            val cached = pdf.items(0)
            PdfFile.open(out).use { again ->
                val fresh = again.items(0)
                assertEquals(ids, fresh.map { it.id })
                cached.zip(fresh).forEach { (was, now) ->
                    if (was is ImageItem) {
                        now as ImageItem
                        assertEquals(was.quarterTurns, now.quarterTurns)
                        listOf(was.box.x0 to now.box.x0, was.box.y0 to now.box.y0, was.box.x1 to now.box.x1, was.box.y1 to now.box.y1)
                            .forEach { (x, y) -> assertEquals(x, y, 0.01f) }
                        val wasPicture = BitmapFactory.decodeByteArray(was.png, 0, was.png.size)
                        val nowPicture = BitmapFactory.decodeByteArray(now.png, 0, now.png.size)
                        assertEquals(wasPicture.width, nowPicture.width)
                        assertEquals(wasPicture.height, nowPicture.height)
                    } else {
                        assertEquals(was, now)
                    }
                }
                for (page in 1..6) assertEquals(pdf.items(page), again.items(page))
            }
        }
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

    /** Erase and lasso read every item each time; a picture is encoded once and comes back the same. */
    @Test fun picturesReadTheSameEachTime() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.apply(AddItems(0, listOf(picture("p"))))
        val first = pdf.items(0).single() as ImageItem
        assertEquals(first, pdf.items(0).single())
        val moved = first.copy(box = Box(10f, 10f, 110f, 110f))
        pdf.apply(ReplaceItems(0, listOf(first), listOf(moved)))
        val after = pdf.items(0).single() as ImageItem
        assertEquals(moved.box, after.box)
        val back = BitmapFactory.decodeByteArray(after.png, 0, after.png.size)
        assertEquals(40, back.width)
    }

    /**
     * Review 4.6: a snip the app pasted or moved is handed back with the PNG it came as. MuPDF
     * isn't asked to make the PNG again, and the bytes stay the same from move to move, so the
     * journal writes the picture once. Only a picture read from a saved file is encoded, once.
     */
    @Test fun aMovedSnipIsNotEncodedAgain() {
        val out = File(dir, "moved-snip.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
            val pasted = picture("p")
            assertTrue(pdf.items(0).isEmpty()) // the page is remembered from here on
            pdf.apply(AddItems(0, listOf(pasted)))
            var held = pdf.items(0).single() as ImageItem
            assertTrue(pasted.png.contentEquals(held.png))
            repeat(3) {
                val moved = held.copy(box = Box(10f + it, 10f, 110f + it, 110f))
                pdf.apply(ReplaceItems(0, listOf(held), listOf(moved)))
                held = pdf.items(0).single() as ImageItem
                assertTrue(pasted.png.contentEquals(held.png))
            }
            assertEquals(pdf.readItems(0), pdf.items(0))
            assertEquals(0, pdf.pictureEncodes)
            pdf.saveTo(out)
        }
        PdfFile.open(out).use { pdf ->
            val read = pdf.items(0).single() as ImageItem
            assertEquals(40, BitmapFactory.decodeByteArray(read.png, 0, read.png.size).width)
            pdf.apply(ReplaceItems(0, listOf(read), listOf(read.copy(box = Box(50f, 50f, 150f, 150f)))))
            assertTrue(read.png.contentEquals((pdf.items(0).single() as ImageItem).png))
            pdf.readItems(0)
            assertEquals(1, pdf.pictureEncodes)
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

    // Review D2: on screen, items stack in the page's own order, as in the file and every other viewer.
    private val across = Stroke(StrokeKind.PEN, (0..50).map { StrokePoint(156f + it * 6, 396f, 0.8f) }) // through the page's middle
    private fun whiteSnip(id: String) = ImageItem(id, Box(200f, 300f, 400f, 500f), 0, whitePng(40, 40)) // covers the stroke's middle

    /** The pixel of a 600-pixel-wide picture under the middle of [across]. */
    private fun PdfFile.middleOfTheStroke(): Pt = pdfToDisplay(0).map(Pt(306f, 396f)).let { Pt(it.x * 600 / 612, it.y * 600 / 612) }
    private fun Bitmap.at(p: Pt) = getPixel(p.x.toInt(), p.y.toInt())

    @Test fun aSnipPastedOverInkHidesItOnScreenToo() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.apply(AddItems(0, listOf(ink("pen", across))))
        val middle = pdf.middleOfTheStroke()
        assertTrue(isDark(pdf.render(0, 600).at(middle)))
        pdf.apply(AddItems(0, listOf(whiteSnip("snip"))))
        assertTrue("the file's own drawing shows the snip", isWhite(pdf.render(0, 600, smoothInk = true).at(middle)))
        val onScreen = pdf.render(0, 600).at(middle)
        assertTrue("on screen ${Integer.toHexString(onScreen)}", isWhite(onScreen))
    }

    @Test fun inkOverASnipStillShows() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.apply(AddItems(0, listOf(whiteSnip("snip"))))
        pdf.apply(AddItems(0, listOf(ink("pen", across))))
        assertEquals(Color.BLACK, pdf.render(0, 600).at(pdf.middleOfTheStroke()))
    }

    @Test fun highlighterOverPenInkLooksAsBefore() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.apply(AddItems(0, listOf(ink("pen", across))))
        val middle = pdf.middleOfTheStroke()
        val penAlone = pdf.render(0, 600).at(middle)
        assertTrue(isDark(penAlone))
        pdf.apply(AddItems(0, listOf(ink("marker", across.copy(kind = StrokeKind.HIGHLIGHTER)))))
        assertEquals(penAlone, pdf.render(0, 600).at(middle))
    }

    /** Write, paste a figure elsewhere on the page, then highlight the earlier writing: the pen stays on top. */
    @Test fun aSnipElsewhereLeavesHighlighterOverPenInkAsBefore() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.apply(AddItems(0, listOf(ink("pen", across))))
        val middle = pdf.middleOfTheStroke()
        val penAlone = pdf.render(0, 600).at(middle)
        assertTrue(isDark(penAlone))
        pdf.apply(AddItems(0, listOf(ImageItem("figure", Box(100f, 650f, 300f, 750f), 0, whitePng(40, 20))))) // far above the stroke
        pdf.apply(AddItems(0, listOf(ink("marker", across.copy(kind = StrokeKind.HIGHLIGHTER)))))
        val now = pdf.render(0, 600).at(middle)
        assertEquals("${Integer.toHexString(penAlone)} became ${Integer.toHexString(now)}", penAlone, now)
    }

    private fun whitePng(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun isWhite(px: Int) = Color.red(px) > 240 && Color.green(px) > 240 && Color.blue(px) > 240
    private fun isRed(px: Int) = Color.red(px) > 180 && Color.green(px) < 80 && Color.blue(px) < 80
    private fun isBlue(px: Int) = Color.blue(px) > 180 && Color.red(px) < 80 && Color.green(px) < 80
}
