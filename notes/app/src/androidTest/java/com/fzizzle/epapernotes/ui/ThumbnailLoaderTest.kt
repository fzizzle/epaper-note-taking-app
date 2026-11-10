package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.library.PdfEntry
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
        val tall = entry(TestPdfs.blank(dir, pages = 1, mediaBox = Box(0f, 0f, 300f, 6000f)))
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

    @Test fun ordinaryPageKeepsTheUsualWidth() {
        val page = entry(TestPdfs.blank(dir, pages = 1))
        loader.show(listOf(page), onScreen = listOf(page))
        assertEquals(THUMB_WIDTH_PX, next().second?.width)
    }
}
