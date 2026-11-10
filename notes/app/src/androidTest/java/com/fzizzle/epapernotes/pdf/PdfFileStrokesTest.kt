package com.fzizzle.epapernotes.pdf

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.doc.RemoveItems
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.pdf.TestPdfs.darkHeightAt
import com.fzizzle.epapernotes.pdf.TestPdfs.isDark
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfFileStrokesTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val line = Stroke(StrokeKind.PEN, (0..50).map { StrokePoint(100f + it * 4, 400f, it / 50f) })

    /** Adds [stroke] as a fresh item and returns its id, the way the session does. */
    private fun PdfFile.add(page: Int, stroke: Stroke): String {
        val id = ItemIds.next()
        apply(AddItems(page, listOf(InkItem(id, stroke))))
        return id
    }

    private fun PdfFile.strokes(page: Int) = items(page).filterIsInstance<InkItem>()

    private fun PdfFile.delete(page: Int, id: String) = apply(RemoveItems(page, items(page).filter { it.id == id }))

    @Test fun strokeRoundTripsThroughSave() {
        val out = File(dir, "ink.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.add(0, line); it.saveTo(out) }
        PdfFile.open(out).use { assertEquals(line, it.strokes(0).single().stroke) }
    }

    @Test fun strokeRendersWhereDrawn_offsetMediaBox() {
        PdfFile.open(TestPdfs.blank(dir, mediaBox = Box(100f, 100f, 712f, 892f))).use { pdf ->
            pdf.add(0, Stroke(StrokeKind.PEN, listOf(StrokePoint(406f, 496f, 1f), StrokePoint(406f, 497f, 1f))))
            val bmp = pdf.render(0, 612) // 1 px per point
            assertTrue(isDark(bmp.getPixel(306, 396))) // the page centre: x 406-100, y 892-496 (PDF y points up)
            pdf.setRotation(0, 90)
            val r = pdf.render(0, 792)
            assertTrue(isDark(r.getPixel(r.width / 2, r.height / 2))) // the centre stays the centre after rotation
        }
    }

    @Test fun appearanceKeepsPressureAfterSave() {
        val out = File(dir, "ap.pdf")
        val wedge = Stroke(StrokeKind.PEN, listOf(StrokePoint(100f, 400f, 0f), StrokePoint(500f, 400f, 1f)))
        PdfFile.open(TestPdfs.blank(dir)).use { it.add(0, wedge); it.saveTo(out) }
        PdfFile.open(out).use {
            val bmp = it.render(0, 2448) // 4 px per point
            assertTrue(darkHeightAt(bmp, x = 480 * 4) > darkHeightAt(bmp, x = 120 * 4) * 2) // thick end is thicker
        }
    }

    @Test fun highlighterIsTranslucent() {
        PdfFile.open(TestPdfs.blank(dir)).use {
            it.add(0, Stroke(StrokeKind.HIGHLIGHTER, listOf(StrokePoint(100f, 400f, 1f), StrokePoint(300f, 400f, 1f))))
            val px = it.render(0, 612).getPixel(200, 392)
            assertTrue(Color.red(px) > 150 && Color.blue(px) < Color.red(px)) // yellow tint, the page still shows through
        }
    }

    @Test fun deleteRemovesOnlyThatStroke() = PdfFile.open(TestPdfs.blank(dir)).use {
        val a = it.add(0, line)
        it.add(0, line)
        it.delete(0, a)
        assertEquals(1, it.strokes(0).size)
    }

    @Test fun foreignAnnotationsSurviveSave() {
        val source = TestPdfs.withForeignAnnotations(dir)
        val before = TestPdfs.foreignSignature(source)
        val out = File(dir, "foreign.pdf")
        PdfFile.open(source).use {
            assertEquals(3, it.foreignAnnotationCount(0))
            assertTrue(it.strokes(0).isEmpty())
            it.add(0, line)
            it.delete(0, it.strokes(0).single().id)
            it.saveTo(out)
        }
        PdfFile.open(out).use { assertEquals(3, it.foreignAnnotationCount(0)) }
        assertEquals(before, TestPdfs.foreignSignature(out))
    }

    /** MuPDF keeps the source file open while it's replaced underneath, which is how autosave will work. */
    @Test fun safeSaveOverSourceFile() {
        val source = TestPdfs.blank(dir)
        PdfFile.open(source).use { pdf ->
            pdf.add(0, line)
            SafeSave.replace(source) { pdf.saveTo(it) }
        }
        PdfFile.open(source).use { assertEquals(1, it.strokes(0).size) }
        assertTrue(!SafeSave.tempFileFor(source).exists())
    }

    /** A page of handwriting must not bloat the file. Wavy strokes like real writing, as in the speed check. */
    @Test fun savedStrokesAreCompact() {
        val empty = File(dir, "empty.pdf")
        val written = File(dir, "written.pdf")
        val source = TestPdfs.blank(dir, pages = 1)
        PdfFile.open(source).use { it.saveTo(empty) }
        PdfFile.open(source).use { pdf ->
            repeat(50) { n ->
                pdf.add(0, Stroke(StrokeKind.PEN, (0 until 200).map { i ->
                    StrokePoint(50f + i * 2.5f, 100f + n * 12f + 4f * kotlin.math.sin(i / 5f), 0.3f + 0.5f * ((i % 20) / 20f))
                }))
            }
            pdf.saveTo(written)
        }
        val perStroke = (written.length() - empty.length()) / 50
        assertTrue("$perStroke bytes per stroke", perStroke < 4_000)
    }

    /** An off-centre stroke moves to where a 90° clockwise turn puts it, and leaves its old spot. */
    @Test fun strokeTurnsWithPage() {
        PdfFile.open(TestPdfs.blank(dir, mediaBox = Box(100f, 100f, 712f, 892f))).use { pdf ->
            // PDF (150, 842) is 50 pt from the left and 50 pt from the top of this offset page.
            pdf.add(0, Stroke(StrokeKind.PEN, listOf(StrokePoint(150f, 842f, 1f), StrokePoint(150f, 843f, 1f))))
            assertTrue(isDark(pdf.render(0, 612).getPixel(50, 50)))
            pdf.setRotation(0, 90)
            val r = pdf.render(0, 792) // now 792 wide, 612 tall
            assertTrue(isDark(r.getPixel(792 - 50, 50))) // top-left has turned to top-right
            assertTrue(!isDark(r.getPixel(50, 50)))
        }
    }

    /**
     * An L: down from PDF (100, 700) to (100, 500), then right to (300, 500). Lopsided on
     * purpose, so a mirrored or turned drawing of it shows up (owner: resized writing went upside down).
     */
    private val ell = Stroke(
        StrokeKind.PEN,
        (0..20).map { StrokePoint(100f, 700f - it * 10, 1f) } + (1..20).map { StrokePoint(100f + it * 10, 500f, 1f) },
    )

    /** Dark where each of [dark] is, in pixels of [bmp], and light at [light]. */
    private fun assertInk(bmp: android.graphics.Bitmap, dark: List<Pair<Int, Int>>, light: Pair<Int, Int>) {
        dark.forEach { (x, y) -> assertTrue("ink expected at $x,$y", isDark(bmp.getPixel(x, y))) }
        assertTrue("no ink expected at $light", !isDark(bmp.getPixel(light.first, light.second)))
    }

    @Test fun lopsidedStrokeIsDrawnTheWayItWasWritten() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.add(0, ell)
        // 1 px per point, y down: the arm's top end at (100, 92), the foot's end at (300, 292).
        assertInk(pdf.render(0, 612), dark = listOf(100 to 100, 290 to 292), light = 290 to 100)
    }

    /** Ink written on a page that's already turned (owner: resized writing went upside down there). */
    @Test fun lopsidedStrokeOnTurnedPageIsDrawnTheWayItWasWritten() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.setRotation(0, 90)
        pdf.add(0, ell)
        // Turned 90° clockwise, PDF (x, y) shows at (y, x): the arm runs from (700, 100) to
        // (500, 100), and the foot down to (500, 300).
        assertInk(pdf.render(0, 792), dark = listOf(690 to 100, 500 to 290), light = 690 to 290)
    }

    /**
     * A file written before the fix: its strokes' drawings carry the page transform as their
     * /Matrix, as MuPDF's setAppearance stored it. Opening redraws them in place, once.
     */
    @Test fun faultyInkDrawingIsRedrawnOnce() {
        val written = File(dir, "faulty.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.add(0, ell); it.add(0, line); it.saveTo(written) }
        val faulty = File(dir, "faulty2.pdf")
        val doc = com.artifex.mupdf.fitz.Document.openDocument(written.path).let { d -> try { d.asPDF()!! } finally { d.destroy() } }
        try {
            val annots = doc.findPage(0).get("Annots")
            for (i in 0 until annots.size()) {
                val m = doc.newArray().apply { listOf(1f, 0f, 0f, -1f, 0f, 792f).forEach { push(it) } }
                annots.get(i).get("AP").get("N").put("Matrix", m)
            }
            doc.save(faulty.path, "")
        } finally {
            doc.destroy()
        }
        PdfFile.open(faulty).use { pdf ->
            // Mirrored in its box, the foot is drawn along the top, at row 792 - 700.
            assertTrue("the old drawing is mirrored", isDark(pdf.render(0, 612).getPixel(290, 92)))
            val idsBefore = pdf.items(0).map { it.id }
            assertTrue(pdf.redrawFaultyInk(0))
            assertInk(pdf.render(0, 612), dark = listOf(100 to 100, 290 to 292), light = 290 to 100)
            assertEquals(idsBefore, pdf.items(0).map { it.id }) // same ids, same drawing order
            assertTrue(!pdf.redrawFaultyInk(0)) // once is enough
        }
    }

    /** Erased handwriting must really leave the file, not just stop being shown. */
    @Test fun erasedStrokeIsGoneFromFile() {
        val first = File(dir, "erase1.pdf")
        val second = File(dir, "erase2.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.add(0, line); it.saveTo(first) }
        PdfFile.open(first).use { it.delete(0, it.strokes(0).single().id); it.saveTo(second) }
        assertTrue(!String(second.readBytes(), Charsets.ISO_8859_1).contains(com.fzizzle.epapernotes.ink.StrokeCodec.KEY))
    }

    /** Stroke ids are what undo will hold on to, so a save must not renumber them. */
    @Test fun strokeIdsStayTheSameAcrossSave() {
        val out = File(dir, "ids.pdf")
        val ids = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
            val a = pdf.add(0, line); pdf.add(0, line); pdf.delete(0, a); pdf.add(0, line)
            pdf.saveTo(out)
            pdf.strokes(0).map { it.id }.toSet()
        }
        PdfFile.open(out).use { assertEquals(ids, it.strokes(0).map { s -> s.id }.toSet()) }
    }

    /** Autosave replaces the open file again and again; every page must stay readable. */
    @Test fun repeatedSafeSaveKeepsEverything() {
        val source = TestPdfs.blank(dir, pages = 3)
        PdfFile.open(source).use { pdf ->
            pdf.add(0, line); SafeSave.replace(source) { pdf.saveTo(it) }
            pdf.add(2, line); SafeSave.replace(source) { pdf.saveTo(it) }
            (0 until pdf.pageCount).forEach { pdf.render(it, 100) }
        }
        PdfFile.open(source).use {
            (0 until it.pageCount).forEach { page -> it.render(page, 100) }
            assertEquals(1, it.strokes(0).size); assertEquals(1, it.strokes(2).size)
        }
    }
}
