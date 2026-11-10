package com.fzizzle.epapernotes.pdf

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.doc.InkRedraw
import com.fzizzle.epapernotes.doc.RemoveItems
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeCodec
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokeOutline
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.ink.StrokeStyle
import com.fzizzle.epapernotes.pdf.TestPdfs.darkHeightAt
import com.fzizzle.epapernotes.pdf.TestPdfs.isDark
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import java.util.Locale
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
            assertEquals(InkRedraw(1, more = true), pdf.redrawFaultyInk(0, limit = 1)) // a part at a time
            assertEquals(InkRedraw(1, more = false), pdf.redrawFaultyInk(0, limit = 1))
            assertInk(pdf.render(0, 612), dark = listOf(100 to 100, 290 to 292), light = 290 to 100)
            assertEquals(idsBefore, pdf.items(0).map { it.id }) // same ids, same drawing order
            assertEquals(InkRedraw(0, more = false), pdf.redrawFaultyInk(0, limit = 25)) // once is enough
        }
    }

    /** The same L, 300 pt to the right: written by plan 2, with no /NM. */
    private val plan2Ell = Stroke(
        StrokeKind.PEN,
        (0..20).map { StrokePoint(400f, 700f - it * 10, 1f) } + (1..15).map { StrokePoint(400f + it * 10, 500f, 1f) },
    )

    /** A highlighter rising from PDF (100, 100) to (300, 300), clear of both Ls: mirrored in its box, it would fall. */
    private val risingHighlight = Stroke(StrokeKind.HIGHLIGHTER, (0..20).map { StrokePoint(100f + it * 10, 100f + it * 10, 1f) })

    private fun isYellow(px: Int) = Color.red(px) > 150 && Color.blue(px) < Color.red(px)

    private fun openRaw(file: File): PDFDocument =
        Document.openDocument(file.path).let { d -> try { d.asPDF()!! } finally { d.destroy() } }

    /**
     * Writes [stroke] the way the app did before 2bfb717, copied from the old addInk: MuPDF's
     * setAppearance with the page transform, which it stores as the drawing's /Matrix.
     * [plan2]: as plan 2 wrote it, with no /NM and pn1 stroke data.
     */
    private fun writeTheOldWay(doc: PDFDocument, page: Int, stroke: Stroke, plan2: Boolean = false) {
        val p = doc.loadPage(page) as PDFPage
        try {
            val style = StrokeStyle.of(stroke.kind)
            val annot = p.createAnnotation(PDFAnnotation.TYPE_INK)
            val toPageSpace = p.transform
            annot.setInkList(arrayOf(stroke.points.map { Point(it.x, it.y).transform(toPageSpace) }.toTypedArray()))
            annot.setColor(style.rgb)
            annot.setOpacity(style.opacity)
            annot.setBorderWidth(style.maxWidth * stroke.widthScale)
            if (plan2) {
                val points = stroke.points.joinToString(" ") { String.format(Locale.ROOT, "%.2f,%.2f,%.3f", it.x, it.y, it.pressure) }
                annot.getObject().put(StrokeCodec.KEY, "pn1 ${if (stroke.kind == StrokeKind.PEN) "pen" else "hl"} $points")
            } else {
                annot.getObject().put("NM", ItemIds.next())
                annot.getObject().put(StrokeCodec.KEY, StrokeCodec.encode(stroke))
            }
            annot.update()
            val box = StrokeOutline.bounds(stroke)
            val contents = "/GS0 gs\n" + StrokeOutline.contentStream(stroke)
            val gs = doc.newDictionary().apply { put("ca", style.opacity) }
            val ext = doc.newDictionary().apply { put("GS0", gs) }
            val resources = doc.newDictionary().apply { put("ExtGState", ext) }
            val buffer = Buffer().apply { writeBytes(contents.toByteArray(Charsets.US_ASCII)) }
            annot.setAppearance(toPageSpace, Rect(box.x0, box.y0, box.x1, box.y1), resources, buffer)
            if (plan2) annot.getObject().delete("NM")
            p.update()
        } finally {
            p.destroy()
        }
    }

    /** For each annotation on each page: does its drawing carry a /Matrix? */
    private fun drawingMatrices(file: File): List<Boolean> {
        val doc = openRaw(file)
        try {
            return (0 until doc.countPages()).flatMap { page ->
                val annots = doc.findPage(page).get("Annots")
                (0 until annots.size()).map { !annots.get(it).get("AP").get("N").get("Matrix").isNull }
            }
        } finally {
            doc.destroy()
        }
    }

    /**
     * A file the app wrote before the fix, with an L in pen, a plan-2 L with no id, a
     * highlighter, and an L on a turned page, all drawn the old way. Opening redraws them in
     * place; once saved and opened again, the ink is where it was written, the ids are the
     * same, and there's nothing left to redraw.
     */
    @Test fun inkWrittenTheOldWayIsRedrawnAndSavedOnce() {
        val file = TestPdfs.blank(dir, pages = 2)
        val doc = openRaw(file)
        try {
            doc.findPage(1).put("Rotate", 90)
            writeTheOldWay(doc, 0, ell)
            writeTheOldWay(doc, 0, plan2Ell, plan2 = true)
            writeTheOldWay(doc, 0, risingHighlight)
            writeTheOldWay(doc, 1, ell)
            doc.save(file.path + ".old", "compress,garbage,objstms") // as the app saved
        } finally {
            doc.destroy()
        }
        val old = File(file.path + ".old")
        assertEquals("written the old way", listOf(true, true, true, true), drawingMatrices(old))

        val ids = PdfFile.open(old).use { pdf -> (0..1).map { page -> pdf.items(page).map { it.id } } }
        assertEquals(listOf(3, 1), ids.map { it.size })
        assertTrue("the plan-2 stroke goes by its object number", ids[0][1].startsWith("o"))
        PdfFile.open(old).use { pdf ->
            assertEquals(InkRedraw(3, more = false), pdf.redrawFaultyInk(0, limit = 25))
            assertEquals(InkRedraw(1, more = false), pdf.redrawFaultyInk(1, limit = 25))
            SafeSave.replace(old) { pdf.saveTo(it) }
        }

        assertEquals("no /Matrix left", listOf(false, false, false, false), drawingMatrices(old))
        PdfFile.open(old).use { pdf ->
            assertEquals(ids, (0..1).map { page -> pdf.items(page).map { it.id } })
            val first = pdf.render(0, 612) // 1 px per point, y down
            assertInk(first, dark = listOf(100 to 100, 290 to 292), light = 290 to 100) // the pen L
            assertInk(first, dark = listOf(400 to 100, 540 to 292), light = 540 to 100) // the plan-2 L
            assertTrue("highlighter where it was written", isYellow(first.getPixel(110, 792 - 110)))
            assertTrue("no highlighter where its mirror would be", !isYellow(first.getPixel(110, 792 - 290)))
            // Turned 90° clockwise, PDF (x, y) shows at (y, x).
            assertInk(pdf.render(1, 792), dark = listOf(690 to 100, 500 to 290), light = 690 to 290)
            assertEquals(InkRedraw(0, more = false), pdf.redrawFaultyInk(0, limit = 25)) // nothing more to do
            assertEquals(InkRedraw(0, more = false), pdf.redrawFaultyInk(1, limit = 25))
        }
    }

    /**
     * Review 1.1: the round ends were drawn the opposite way round to the body, so the two
     * cancelled where they overlapped, leaving a half-circle hole just inside each end.
     */
    @Test fun highlighterIsSolidJustInsideItsEnd() = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
        pdf.add(0, Stroke(StrokeKind.HIGHLIGHTER, listOf(StrokePoint(100f, 400f, 1f), StrokePoint(300f, 400f, 1f))))
        val half = Math.round(StrokeStyle.of(StrokeKind.HIGHLIGHTER).widthAt(1f) / 4) // half a radius
        val bmp = pdf.render(0, 612) // 1 px per point, y down
        assertTrue("solid just inside the start", isYellow(bmp.getPixel(100 + half, 792 - 400)))
        assertTrue("solid just inside the end", isYellow(bmp.getPixel(300 - half, 792 - 400)))
        assertTrue("solid in the middle", isYellow(bmp.getPixel(200, 792 - 400)))
    }

    /** A stroke drawn before the ends were fixed has no drawing version: it's drawn again, once. */
    @Test fun oldDrawingIsRedrawnOnce() {
        val written = File(dir, "old-drawing.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
            pdf.add(0, line)
            assertEquals("a new stroke needs no redraw", InkRedraw(0, more = false), pdf.redrawFaultyInk(0, 25))
            pdf.saveTo(written)
        }
        val old = File(dir, "old-drawing2.pdf")
        val doc = openRaw(written)
        try {
            doc.findPage(0).get("Annots").get(0).delete(ItemAnnotations.DRAWING_KEY)
            doc.save(old.path, "")
        } finally {
            doc.destroy()
        }
        val again = File(dir, "old-drawing3.pdf")
        PdfFile.open(old).use { pdf ->
            val ids = pdf.items(0).map { it.id }
            assertEquals(InkRedraw(1, more = false), pdf.redrawFaultyInk(0, 25))
            assertEquals(InkRedraw(0, more = false), pdf.redrawFaultyInk(0, 25))
            assertEquals(ids, pdf.items(0).map { it.id })
            pdf.saveTo(again)
        }
        // And it stays fixed once saved.
        PdfFile.open(again).use { assertEquals(InkRedraw(0, more = false), it.redrawFaultyInk(0, 25)) }
    }

    /** One old stroke whose points can't be read doesn't stop the others being redrawn and counted. */
    @Test fun aStrokeThatCantBeRedrawnLeavesTheRestRedrawn() {
        val file = TestPdfs.blank(dir, pages = 1)
        val doc = openRaw(file)
        try {
            writeTheOldWay(doc, 0, ell)
            writeTheOldWay(doc, 0, plan2Ell)
            doc.findPage(0).get("Annots").get(0).put(StrokeCodec.KEY, "pn2 pen 1.000 broken")
            doc.save(file.path + ".old", "")
        } finally {
            doc.destroy()
        }
        PdfFile.open(File(file.path + ".old")).use { pdf ->
            assertEquals(InkRedraw(1, more = false), pdf.redrawFaultyInk(0, limit = 25))
            assertEquals(InkRedraw(0, more = false), pdf.redrawFaultyInk(0, limit = 25)) // not tried again
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
