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
}
