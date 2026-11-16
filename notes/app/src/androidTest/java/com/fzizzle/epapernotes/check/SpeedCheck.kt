package com.fzizzle.epapernotes.check

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.pdf.PdfFile
import com.fzizzle.epapernotes.pdf.TestPdfs
import com.fzizzle.epapernotes.storage.FileIdentity
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import java.util.Locale
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures MuPDF on the tablet with the owner's real PDFs. It's skipped unless PDFs have been
 * copied into the app's `files/check/` folder, so it never runs by accident.
 *
 * It never touches the owner's file: everything happens on a copy in the cache folder.
 *
 * [addingStrokesToAFullPage] is the exception to the first rule: it makes its own blank PDF,
 * so it always runs. It is also the one that can fail: a stroke on a full page must not cost
 * more than twice a stroke on a nearly empty one.
 */
@RunWith(AndroidJUnit4::class)
class SpeedCheck {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val checkDir = File(context.getExternalFilesDir(null), "check")
    private val results = File(checkDir, "results.txt")

    @Test fun measure() {
        // The app must create this folder itself. A folder made over adb belongs to the shell,
        // and Android then hides its files from the app.
        checkDir.mkdirs()
        val pdfs = checkDir.listFiles { f -> f.name.endsWith(".pdf", ignoreCase = true) }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no PDFs in $checkDir", pdfs.isNotEmpty())
        report("==== Speed check: ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, MuPDF 1.28.5")
        pdfs.forEach { measureOne(it) }
    }

    private fun measureOne(original: File) {
        val copy = File(context.cacheDir, "speed-check.pdf")
        original.copyTo(copy, overwrite = true)
        report("---- ${original.name}: ${"%.1f".format(Locale.ROOT, original.length() / 1e6)} MB")

        val (pdf, openMs) = timed { PdfFile.open(copy) }
        var already = 0
        pdf.use {
            report("pages: ${it.pageCount}")
            report("open: $openMs ms")
            report("fingerprint: ${timed { FileIdentity.of(copy) }.second} ms")
            // The file may hold the owner's own strokes on page 1 already.
            already = it.items(0).size

            val renders = (0 until minOf(10, it.pageCount)).map { page -> timed { it.render(page, SCREEN_WIDTH_PX) }.second }
            report("render page 1 (cold): ${renders.first()} ms")
            if (renders.size > 1) report("render pages 2-${renders.size} (median): ${median(renders.drop(1))} ms, slowest ${renders.drop(1).max()} ms")

            // Baseline: how much a save changes the size with no strokes at all. Separates
            // "MuPDF writes the file differently" from "the strokes take space".
            val unchanged = File(context.cacheDir, "speed-check-unchanged.pdf")
            it.saveTo(unchanged)
            report("save with no changes: size ${"%.1f".format(Locale.ROOT, unchanged.length() / 1e6)} MB")
            unchanged.delete()

            val addMs = timed { repeat(STROKES) { n -> it.apply(AddItems(0, listOf(InkItem(ItemIds.next(), wavyStroke(n))))) } }.second
            report("add $STROKES strokes of $POINTS points: $addMs ms")

            val saveMs = timed { SafeSave.replace(copy) { tmp -> it.saveTo(tmp) } }.second
            report("save (safe replace): $saveMs ms, new size ${"%.1f".format(Locale.ROOT, copy.length() / 1e6)} MB")
        }

        val (back, reopenMs) = timed { PdfFile.open(copy).use { it.items(0).size } }
        report("reopen + read strokes: $reopenMs ms, strokes back: $back")
        assertEquals(already + STROKES, back)
        copy.delete()
    }

    /**
     * Does adding a stroke get slower as the page fills up? Needs none of the owner's PDFs: it
     * writes on a blank page of its own in the cache folder, and only logs (tag SpeedCheck),
     * so the owner's check folder isn't touched.
     */
    @Test fun addingStrokesToAFullPage() {
        val blank = TestPdfs.blank(context.cacheDir, pages = 1)
        val saved = File(context.cacheDir, "speed-check-full-page.pdf")
        try {
            log("==== Adding strokes to a full page: ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, MuPDF 1.28.5")
            PdfFile.open(blank).use { pdf ->
                val eachNs = (0 until FULL_PAGE_STROKES).map { n ->
                    val stroke = shortStroke(n) // made outside the timing: only the adding counts
                    val start = System.nanoTime()
                    pdf.apply(AddItems(0, listOf(InkItem(ItemIds.next(), stroke))))
                    System.nanoTime() - start
                }
                // The median of fifty in a row, so one unlucky stroke (a pause to tidy memory) doesn't decide the number.
                for (last in listOf(50, 500, 1000, 1500)) {
                    log("add stroke ${last - 49}-$last (median): ${ms(median(eachNs.subList(last - 50, last)))} ms")
                }
                log("add $FULL_PAGE_STROKES strokes of $SHORT_POINTS points: ${ms(eachNs.sum())} ms")

                val saveMs = timed { pdf.saveTo(saved) }.second
                log("saveTo with $FULL_PAGE_STROKES strokes: $saveMs ms, size ${saved.length() / 1000} kB")

                val (items, itemsMs) = timed { pdf.items(0) }
                log("items(0) with $FULL_PAGE_STROKES strokes: $itemsMs ms")
                assertEquals(FULL_PAGE_STROKES, items.size)
                // What every erase and lasso after the first on a page pays: the items are remembered.
                val (again, againMs) = timed { pdf.items(0) }
                log("items(0) again: $againMs ms")
                assertEquals(items, again)
                // Of the first read, how much is parsing the stroke text alone (the rest is MuPDF).
                val texts = items.map { com.fzizzle.epapernotes.ink.StrokeCodec.encode((it as com.fzizzle.epapernotes.ink.InkItem).stroke) }
                val (_, decodeMs) = timed { texts.forEach { com.fzizzle.epapernotes.ink.StrokeCodec.decode(it) } }
                log("decode $FULL_PAGE_STROKES stroke texts alone: $decodeMs ms")

                // Drawing the full page, pen ink only: the case PdfFile.drawWithSolidInk must keep fast
                // (137 ms on 2026-11-10). The checksum shows whether a change to the drawing changed a pixel.
                val picture = pdf.render(0, FITTED_WIDTH_PX)
                val pixels = IntArray(picture.width * picture.height).also { picture.getPixels(it, 0, picture.width, 0, 0, picture.width, picture.height) }
                picture.recycle()
                log("checksum of $FULL_PAGE_STROKES strokes at width $FITTED_WIDTH_PX: ${pixels.contentHashCode()}")
                val draws = (1..5).map { timed { pdf.render(0, FITTED_WIDTH_PX).recycle() }.second }
                log("render $FULL_PAGE_STROKES strokes at width $FITTED_WIDTH_PX: median ${median(draws)} ms, all $draws")

                // Review speed, focus 1 (addingAStrokeDoesNotGrowWithThePage): on a full page a
                // stroke may cost up to three times what it does on a nearly empty one, no more.
                // (Loading the page still grows a little with what's on it: 2.5 ms against
                // 5.4 ms when measured, where it used to be 4 ms against 44 ms.)
                // Last, so every number above is logged even when this fails.
                val early = median(eachNs.subList(50, 100))
                val late = median(eachNs.subList(FULL_PAGE_STROKES - 50, FULL_PAGE_STROKES))
                assertTrue("stroke 1451-1500 took ${ms(late)} ms, stroke 51-100 took ${ms(early)} ms", late < 3 * early)
            }
        } finally {
            blank.delete()
            saved.delete()
        }
    }

    /**
     * How long a page full of pen ink takes to draw, at the fitted width and at the widest a
     * page picture gets (1.5 views). The ink is spread from the top-left corner to the
     * bottom-right, so the solid-ink step (PdfFile.drawSolid) has the whole page to cover.
     * Logs only (tag SpeedCheck).
     */
    @Test fun renderingAnInkedPage() {
        val blank = TestPdfs.blank(context.cacheDir, pages = 1)
        try {
            log("==== Rendering an inked page: ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, MuPDF 1.28.5")
            PdfFile.open(blank).use { pdf ->
                val size = pdf.displaySize(0)
                repeat(INKED_STROKES) { n -> pdf.apply(AddItems(0, listOf(InkItem(ItemIds.next(), spreadStroke(n, size.width, size.height))))) }
                // The same page must give the same number on any build: a way to see that a
                // change to how ink is drawn changed no pixel.
                val picture = pdf.render(0, FITTED_WIDTH_PX)
                val pixels = IntArray(picture.width * picture.height).also { picture.getPixels(it, 0, picture.width, 0, 0, picture.width, picture.height) }
                picture.recycle()
                log("checksum at width $FITTED_WIDTH_PX: ${pixels.contentHashCode()}")
                for (width in listOf(FITTED_WIDTH_PX, WIDEST_WIDTH_PX)) {
                    val times = (1..5).map { timed { pdf.render(0, width).recycle() }.second }
                    log("render $INKED_STROKES strokes at width $width: median ${median(times)} ms, all $times")
                }
            }
        } finally {
            blank.delete()
        }
    }

    /** Stroke [n] of [INKED_STROKES], a short wiggle: they run from the page's top-left corner to its bottom-right. */
    private fun spreadStroke(n: Int, pageWidth: Float, pageHeight: Float): Stroke {
        val x = 20f + (pageWidth - 80f) * (n % 20) / 19f
        val y = 20f + (pageHeight - 40f) * (n / 20) / (INKED_STROKES / 20 - 1)
        return Stroke(StrokeKind.PEN, (0 until SHORT_POINTS).map { i ->
            StrokePoint(x + i * 0.5f, y + 4f * sin(i / 5f), 0.3f + 0.5f * ((i % 20) / 20f))
        })
    }

    /** A short wiggle of [SHORT_POINTS] points, about a written letter wide. Fifty rows of thirty fill the page. */
    private fun shortStroke(n: Int): Stroke {
        val x = 30f + (n % 30) * 18.5f
        val y = 30f + (n / 30) * 14.8f
        return Stroke(StrokeKind.PEN, (0 until SHORT_POINTS).map { i ->
            StrokePoint(x + i * 0.5f, y + 4f * sin(i / 5f), 0.3f + 0.5f * ((i % 20) / 20f))
        })
    }

    /** A handwriting-like wiggle across the page, a different line for each [n]. */
    private fun wavyStroke(n: Int) = Stroke(StrokeKind.PEN, (0 until POINTS).map { i ->
        StrokePoint(50f + i * 2.5f, 100f + n * 12f + 4f * sin(i / 5f), 0.3f + 0.5f * ((i % 20) / 20f))
    })

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1_000_000
    }

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    /** Nanoseconds as milliseconds with two decimals: one stroke takes too little for whole ones. */
    private fun ms(ns: Long) = "%.2f".format(Locale.ROOT, ns / 1e6)

    private fun report(line: String) {
        log(line)
        results.appendText(line + "\n")
    }

    /** To the tablet's log only, for a test that must work without the owner's check folder. */
    private fun log(line: String) {
        Log.i("SpeedCheck", line)
    }

    private companion object {
        const val SCREEN_WIDTH_PX = 1860 // the Note Air 6 C's portrait width
        const val STROKES = 50
        const val INKED_STROKES = 300
        const val FITTED_WIDTH_PX = 1742 // a page fitted to the screen, as the app's log shows it
        const val WIDEST_WIDTH_PX = 2790 // 1.5 views: PageView.MAX_PICTURE_WIDTH
        const val POINTS = 200
        const val FULL_PAGE_STROKES = 1500
        const val SHORT_POINTS = 30
    }
}
