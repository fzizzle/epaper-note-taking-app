package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.library.PdfFacts
import com.fzizzle.epapernotes.library.THUMB_MAX_HEIGHT_PX
import com.fzizzle.epapernotes.library.THUMB_WIDTH_PX
import com.fzizzle.epapernotes.library.ThumbnailCache
import com.fzizzle.epapernotes.pdf.TestPdfs
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailLoaderTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "thumb-test").apply { deleteRecursively(); mkdirs() }
    private val answers = LinkedBlockingQueue<Pair<PdfEntry, Bitmap?>>()
    private val cache = ThumbnailCache(File(dir, "cache"))
    private val loader = ThumbnailLoader(cache) { pdf, bmp -> answers += pdf to bmp }

    @After fun tearDown() {
        loader.stop()
        dir.deleteRecursively()
    }

    private fun entry(file: File) = PdfEntry(file, file.name, file.length(), file.lastModified())

    private fun next() = answers.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no answer from the loader")

    /** Review: a file that no longer renders tells its tile, which drops its old picture for the grey page. */
    @Test fun aPdfThatCantBeDrawnIsAnsweredWithNoPicture() {
        val good = entry(TestPdfs.blank(dir))
        val bad = entry(TestPdfs.garbage(dir))
        loader.show(listOf(good, bad), onScreen = listOf(good, bad))
        val first = next()
        val second = next()
        assertEquals(good, first.first)
        assertNotNull(first.second)
        assertEquals(bad to null, second)
    }

    /** Review 2.3: a very tall first page gets a narrower thumbnail, not a huge one. */
    @Test fun tallFirstPageIsCapped() {
        val tall = entry(TestPdfs.blank(dir, pages = 1, mediaBox = Box(0f, 0f, 300f, 3000f)))
        loader.show(listOf(tall), onScreen = listOf(tall))
        val bitmap = next().second ?: throw AssertionError("no thumbnail for the tall page")
        assertTrue("${bitmap.width}x${bitmap.height}", bitmap.height <= THUMB_MAX_HEIGHT_PX && bitmap.width >= 20)
    }

    /**
     * Refresh (speed plan, decision 2): the tile on screen is drawn again at once; another
     * PDF in the folder loses its old thumbnail but is only drawn again when it comes on screen.
     */
    @Test fun refreshDrawsTheRestOnlyWhenTheyComeOnScreen() {
        val shown = entry(TestPdfs.blank(dir))
        val other = entry(TestPdfs.blank(dir).copyTo(File(dir, "other.pdf")))
        loader.show(listOf(shown, other), onScreen = listOf(shown))
        assertEquals(shown, next().first)
        // The background pass puts the other one in the cache.
        val deadline = System.currentTimeMillis() + 10_000
        while (cache.cached(other) == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertNotNull("the other PDF was never cached", cache.cached(other))

        loader.redraw(onScreen = listOf(shown), rest = listOf(other))
        val again = next()
        assertEquals(shown, again.first)
        assertNotNull(again.second)
        // The other's old thumbnail is gone, and nothing draws it again in the background.
        Thread.sleep(1500)
        assertEquals(null, cache.cached(other))
        assertEquals(null, loader.bitmapFor(other))

        loader.show(listOf(shown, other), onScreen = listOf(shown, other))
        val scrolledTo = next()
        assertEquals(other, scrolledTo.first)
        assertNotNull(scrolledTo.second)
        assertNotNull(cache.cached(other))
    }

    /**
     * A thumbnail read back from the cache is 16-bit, like one just rendered: half the memory.
     * Android ignores the 16-bit wish for a PNG that has a see-through channel, so the cached
     * PNG must be stored without one.
     */
    @Test fun aCachedThumbnailIsReadAt16Bit() {
        val page = entry(TestPdfs.blank(dir, pages = 1))
        loader.show(listOf(page), onScreen = listOf(page))
        assertEquals(Bitmap.Config.RGB_565, next().second?.config) // rendered
        assertNotNull(cache.cached(page))
        // A second loader, as after the app starts again: memory is empty, the cache has it.
        val again = LinkedBlockingQueue<Pair<PdfEntry, Bitmap?>>()
        val second = ThumbnailLoader(cache) { pdf, bmp -> again += pdf to bmp }
        try {
            second.show(listOf(page), onScreen = listOf(page))
            val fromCache = again.poll(10, TimeUnit.SECONDS)?.second ?: throw AssertionError("no thumbnail from the cache")
            assertEquals(Bitmap.Config.RGB_565, fromCache.config)
        } finally {
            second.stop()
        }
    }

    /** The page count and "has writing" come with the thumbnail, read from the PDF itself, and are kept with it. */
    @Test fun aRenderedThumbnailBringsItsFacts() {
        val clean = entry(TestPdfs.blank(dir, pages = 3))
        val written = TestPdfs.blank(dir, pages = 3).copyTo(File(dir, "written.pdf"))
        val stroke = com.fzizzle.epapernotes.ink.Stroke(
            com.fzizzle.epapernotes.ink.StrokeKind.PEN,
            (0..10).map { com.fzizzle.epapernotes.ink.StrokePoint(100f + it * 5, 300f, 0.5f) },
        )
        com.fzizzle.epapernotes.pdf.PdfFile.open(written).use {
            // On the second page: the look must go past the first.
            it.apply(com.fzizzle.epapernotes.doc.AddItems(1, listOf(com.fzizzle.epapernotes.ink.InkItem(com.fzizzle.epapernotes.ink.ItemIds.next(), stroke))))
            val out = File(dir, "written-saved.pdf")
            it.saveTo(out)
        }
        val inked = entry(File(dir, "written-saved.pdf"))
        loader.show(listOf(clean, inked), onScreen = listOf(clean, inked))
        next(); next()
        assertEquals(PdfFacts(3, false), loader.factsFor(clean))
        assertEquals(PdfFacts(3, true), loader.factsFor(inked))
        assertEquals(PdfFacts(3, true), cache.facts(inked))
        // And again from the cache, as after the app starts afresh.
        val again = LinkedBlockingQueue<Pair<PdfEntry, Bitmap?>>()
        val second = ThumbnailLoader(cache) { pdf, bmp -> again += pdf to bmp }
        try {
            second.show(listOf(inked), onScreen = listOf(inked))
            again.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no thumbnail from the cache")
            assertEquals(PdfFacts(3, true), second.factsFor(inked))
        } finally {
            second.stop()
        }
    }

    /**
     * The list view shows no pictures, but still the writing mark and the page count: the
     * loader finds a PDF's facts without drawing it, and says when it has them.
     */
    @Test fun factsComeWithoutAPictureForTheList() {
        val page = entry(TestPdfs.blank(dir, pages = 3))
        val told = LinkedBlockingQueue<PdfEntry>()
        val forList = ThumbnailLoader(cache, onFacts = { told += it }) { pdf, bmp -> answers += pdf to bmp }
        try {
            forList.showFacts(listOf(page))
            assertEquals(page, told.poll(10, TimeUnit.SECONDS))
            assertEquals(PdfFacts(3, false), forList.factsFor(page))
            assertEquals(PdfFacts(3, false), cache.facts(page))
            assertEquals(null, cache.cached(page)) // nothing was drawn
            assertEquals(null, answers.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            forList.stop()
        }
    }

    /**
     * A thumbnail cached before facts were kept beside it: it is shown from the cache, and its
     * page count and writing mark are worked out from the PDF without drawing the page again.
     */
    @Test fun aCachedThumbnailWithoutFactsIsNotDrawnAgain() {
        val page = entry(TestPdfs.blank(dir, pages = 3))
        val picture = Bitmap.createBitmap(240, 310, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE); setHasAlpha(false) }
        cache.store(page, java.io.ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
        assertEquals(null, cache.facts(page))
        loader.show(listOf(page), onScreen = listOf(page))
        assertNotNull(next().second)
        assertEquals(0, loader.draws)
        assertEquals(PdfFacts(3, false), loader.factsFor(page))
        assertEquals(PdfFacts(3, false), cache.facts(page))
    }

    private fun cachedEntry(name: String): PdfEntry {
        val page = entry(TestPdfs.blank(dir, pages = 1).let { f -> File(dir, name).also { f.renameTo(it) } })
        val picture = Bitmap.createBitmap(240, 310, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE); setHasAlpha(false) }
        cache.store(page, java.io.ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
        cache.storeFacts(page, PdfFacts(1, false))
        return page
    }

    /** Before a folder is shown: the visible tiles' thumbnails read from the cache, none drawn. */
    @Test fun warmReadsCachedThumbnailsWithoutDrawing() {
        loader.pause() // the loader's own thread stays out of it
        val a = cachedEntry("a.pdf")
        val b = cachedEntry("b.pdf")
        val missing = entry(TestPdfs.blank(dir, pages = 1).let { f -> File(dir, "c.pdf").also { f.renameTo(it) } })
        assertEquals(2, loader.warm(listOf(a, missing, b), budgetMs = 1000))
        assertNotNull(loader.bitmapFor(a))
        assertNotNull(loader.bitmapFor(b))
        assertEquals(null, loader.bitmapFor(missing))
        assertEquals(PdfFacts(1, false), loader.factsFor(a))
        assertEquals(0, loader.draws)
    }

    @Test fun warmStopsAtItsBudget() {
        loader.pause()
        val a = cachedEntry("a.pdf")
        assertEquals(0, loader.warm(listOf(a), budgetMs = 0))
        assertEquals(null, loader.bitmapFor(a))
    }

    @Test fun ordinaryPageKeepsTheUsualWidth() {
        val page = entry(TestPdfs.blank(dir, pages = 1))
        loader.show(listOf(page), onScreen = listOf(page))
        assertEquals(240, next().second?.width)
    }

    /** One made before thumbnails were made smaller, 300 by 424: shown as it is, not drawn again. */
    @Test fun anOldBiggerThumbnailStillFits() {
        val page = entry(TestPdfs.blank(dir, pages = 1))
        val old = Bitmap.createBitmap(300, 424, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE); setHasAlpha(false) }
        cache.store(page, java.io.ByteArrayOutputStream().also { old.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
        cache.storeFacts(page, PdfFacts(1, false))
        loader.show(listOf(page), onScreen = listOf(page))
        assertEquals(300, next().second?.width)
        assertEquals(0, loader.draws)
    }
}
