package com.fzizzle.epapernotes.pdf

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.ink.Box
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

    @Test fun strokeRoundTripsThroughSave() {
        val out = File(dir, "ink.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.addStroke(0, line); it.saveTo(out) }
        PdfFile.open(out).use { assertEquals(line, it.strokes(0).single().stroke) }
    }

    @Test fun strokeRendersWhereDrawn_offsetMediaBox() {
        PdfFile.open(TestPdfs.blank(dir, mediaBox = Box(100f, 100f, 712f, 892f))).use { pdf ->
            pdf.addStroke(0, Stroke(StrokeKind.PEN, listOf(StrokePoint(406f, 496f, 1f), StrokePoint(406f, 497f, 1f))))
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
        PdfFile.open(TestPdfs.blank(dir)).use { it.addStroke(0, wedge); it.saveTo(out) }
        PdfFile.open(out).use {
            val bmp = it.render(0, 2448) // 4 px per point
            assertTrue(darkHeightAt(bmp, x = 480 * 4) > darkHeightAt(bmp, x = 120 * 4) * 2) // thick end is thicker
        }
    }

    @Test fun highlighterIsTranslucent() {
        PdfFile.open(TestPdfs.blank(dir)).use {
            it.addStroke(0, Stroke(StrokeKind.HIGHLIGHTER, listOf(StrokePoint(100f, 400f, 1f), StrokePoint(300f, 400f, 1f))))
            val px = it.render(0, 612).getPixel(200, 392)
            assertTrue(Color.red(px) > 150 && Color.blue(px) < Color.red(px)) // yellow tint, the page still shows through
        }
    }

    @Test fun deleteRemovesOnlyThatStroke() = PdfFile.open(TestPdfs.blank(dir)).use {
        val a = it.addStroke(0, line)
        it.addStroke(0, line)
        it.deleteStroke(0, a)
        assertEquals(1, it.strokes(0).size)
    }

    @Test fun foreignAnnotationsSurviveSave() {
        val source = TestPdfs.withForeignAnnotations(dir)
        val before = TestPdfs.foreignSignature(source)
        val out = File(dir, "foreign.pdf")
        PdfFile.open(source).use {
            assertEquals(3, it.foreignAnnotationCount(0))
            assertTrue(it.strokes(0).isEmpty())
            it.addStroke(0, line)
            it.deleteStroke(0, it.strokes(0).single().id)
            it.saveTo(out)
        }
        PdfFile.open(out).use { assertEquals(3, it.foreignAnnotationCount(0)) }
        assertEquals(before, TestPdfs.foreignSignature(out))
    }

    /** MuPDF keeps the source file open while it's replaced underneath, which is how autosave will work. */
    @Test fun safeSaveOverSourceFile() {
        val source = TestPdfs.blank(dir)
        PdfFile.open(source).use { pdf ->
            pdf.addStroke(0, line)
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
                pdf.addStroke(0, Stroke(StrokeKind.PEN, (0 until 200).map { i ->
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
            pdf.addStroke(0, Stroke(StrokeKind.PEN, listOf(StrokePoint(150f, 842f, 1f), StrokePoint(150f, 843f, 1f))))
            assertTrue(isDark(pdf.render(0, 612).getPixel(50, 50)))
            pdf.setRotation(0, 90)
            val r = pdf.render(0, 792) // now 792 wide, 612 tall
            assertTrue(isDark(r.getPixel(792 - 50, 50))) // top-left has turned to top-right
            assertTrue(!isDark(r.getPixel(50, 50)))
        }
    }

    /** Erased handwriting must really leave the file, not just stop being shown. */
    @Test fun erasedStrokeIsGoneFromFile() {
        val first = File(dir, "erase1.pdf")
        val second = File(dir, "erase2.pdf")
        PdfFile.open(TestPdfs.blank(dir)).use { it.addStroke(0, line); it.saveTo(first) }
        PdfFile.open(first).use { it.deleteStroke(0, it.strokes(0).single().id); it.saveTo(second) }
        assertTrue(!String(second.readBytes(), Charsets.ISO_8859_1).contains(com.fzizzle.epapernotes.ink.StrokeCodec.KEY))
    }

    /** Stroke ids are what undo will hold on to, so a save must not renumber them. */
    @Test fun strokeIdsStayTheSameAcrossSave() {
        val out = File(dir, "ids.pdf")
        val ids = PdfFile.open(TestPdfs.blank(dir)).use { pdf ->
            val a = pdf.addStroke(0, line); pdf.addStroke(0, line); pdf.deleteStroke(0, a); pdf.addStroke(0, line)
            pdf.saveTo(out)
            pdf.strokes(0).map { it.id }.toSet()
        }
        PdfFile.open(out).use { assertEquals(ids, it.strokes(0).map { s -> s.id }.toSet()) }
    }

    /** Autosave replaces the open file again and again; every page must stay readable. */
    @Test fun repeatedSafeSaveKeepsEverything() {
        val source = TestPdfs.blank(dir, pages = 3)
        PdfFile.open(source).use { pdf ->
            pdf.addStroke(0, line); SafeSave.replace(source) { pdf.saveTo(it) }
            pdf.addStroke(2, line); SafeSave.replace(source) { pdf.saveTo(it) }
            (0 until pdf.pageCount).forEach { pdf.render(it, 100) }
        }
        PdfFile.open(source).use {
            (0 until it.pageCount).forEach { page -> it.render(page, 100) }
            assertEquals(1, it.strokes(0).size); assertEquals(1, it.strokes(2).size)
        }
    }
}
