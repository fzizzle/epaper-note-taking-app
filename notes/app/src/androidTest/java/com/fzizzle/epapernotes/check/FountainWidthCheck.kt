package com.fzizzle.epapernotes.check

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.ink.StrokeStyle
import com.fzizzle.epapernotes.pdf.PdfFile
import com.fzizzle.epapernotes.pdf.TestPdfs
import com.fzizzle.epapernotes.ui.PenPoints
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.NeoFountainPenWrapper
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: a measurement. How thick Onyx's fountain pen draws a straight line at each
 * pressure, next to how thick the app's saved ink is at the same pressure, both in screen
 * pixels at the same scale. The owner sees the ink thin when the app's copy replaces Onyx's
 * live line (2026-11-10). Logged under the tag "Width"; nothing is asserted.
 */
@RunWith(AndroidJUnit4::class)
class FountainWidthCheck {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun compareWidths() {
        com.onyx.android.sdk.base.utils.ResManager.init(context) // Onyx's pen code reads screen density through it
        val pxPerPt = 2.5f
        val liveWidth = StrokeStyle.of(StrokeKind.PEN).liveFountainWidth(pxPerPt)
        val dir = File(context.cacheDir, "fountain-check").apply { deleteRecursively(); mkdirs() }
        Log.i("Width", "pxPerPt=$pxPerPt liveWidth=$liveWidth px")
        for (p in listOf(0.05f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.7f, 1f)) {
            // A straight line across, 100 points 8 ms apart, as the pen reports it (pixels, raw pressure).
            val raw = (0 until 100).map { i ->
                TouchPoint(100f + i * 8f, 200f, p * PenPoints.MAX_PRESSURE, 0f, 1000L + i * 8)
            }
            val onyx = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false; style = Paint.Style.FILL }
            val sizes = runCatching {
                NeoFountainPenWrapper.computeStrokePoints(raw, liveWidth, liveWidth, PenPoints.MAX_PRESSURE)
                    ?.map { it.size }
            }.getOrElse { Log.w("Width", "compute failed", it); null }
            runCatching {
                NeoFountainPenWrapper.drawStroke(Canvas(onyx), paint, raw, liveWidth, liveWidth, PenPoints.MAX_PRESSURE, false)
            }.onFailure { Log.w("Width", "draw failed", it) }
            val onyxPx = thickness(onyx, 500)

            // The app's saved ink: the same line in PDF points, rendered as the page is.
            val file = TestPdfs.blank(dir, pages = 1)
            val appPx = PdfFile.open(file).use { pdf ->
                pdf.apply(AddItems(0, listOf(InkItem(ItemIds.next(), Stroke(StrokeKind.PEN, (0 until 100).map { i -> StrokePoint((100f + i * 8f) / pxPerPt, 200f / pxPerPt, p) })))))
                thickness(pdf.render(0, Math.round(612 * pxPerPt)), 500)
            }
            val expected = StrokeStyle.of(StrokeKind.PEN).widthAt(p, pxPerPt)
            val mid = sizes?.let { it[it.size / 2] }
            Log.i("Width", "pressure=$p onyx=${onyxPx}px app=${appPx}px appFormula=${"%.2f".format(expected)}px onyxPointSize(mid)=$mid first=${sizes?.firstOrNull()} last=${sizes?.lastOrNull()}")
        }
    }

    /** Onyx's point widths against the fitted curve width × (0.4 + 1.2 p²), at two widths and three speeds. */
    @Test fun fitCurve() {
        com.onyx.android.sdk.base.utils.ResManager.init(context)
        for (w in listOf(3f, 5f, 9f)) for (step in listOf(1f, 8f, 40f)) {
            val rows = (0..10).map { it / 10f }.map { p ->
                val raw = (0 until 60).map { i -> TouchPoint(100f + i * step, 200f, p * PenPoints.MAX_PRESSURE, 0f, 1000L + i * 8) }
                val sizes = NeoFountainPenWrapper.computeStrokePoints(raw, w, w, PenPoints.MAX_PRESSURE)!!.map { it.size }
                val fit = w * (0.4f + 1.2f * p * p)
                "p=%.1f onyx=%.2f..%.2f fit=%.2f".format(p, sizes.minOrNull(), sizes.maxOrNull(), fit)
            }
            Log.i("Width", "width=$w step=$step: " + rows.joinToString(" | "))
        }
        // Pressure changing along the stroke: does each point follow its own pressure?
        val ramp = (0 until 60).map { i -> TouchPoint(100f + i * 8f, 200f, (i / 59f) * PenPoints.MAX_PRESSURE, 0f, 1000L + i * 8) }
        val sizes = NeoFountainPenWrapper.computeStrokePoints(ramp, 5f, 5f, PenPoints.MAX_PRESSURE)!!.map { it.size }
        Log.i("Width", "ramp: " + sizes.withIndex().filter { it.index % 6 == 0 }.joinToString(" ") { "%.2f(fit %.2f)".format(it.value, 5f * (0.4f + 1.2f * (it.index / 59f) * (it.index / 59f))) })
    }

    /** Dark pixels in column [x]: how thick a horizontal line is there. */
    private fun thickness(bmp: Bitmap, x: Int): Int =
        (0 until bmp.height).count { Color.red(bmp.getPixel(x, it)) < 128 }
}
