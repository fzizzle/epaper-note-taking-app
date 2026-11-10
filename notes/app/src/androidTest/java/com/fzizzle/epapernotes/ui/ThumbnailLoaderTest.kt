package com.fzizzle.epapernotes.ui

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.library.ThumbnailCache
import com.fzizzle.epapernotes.pdf.TestPdfs
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailLoaderTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "thumb-test").apply { deleteRecursively(); mkdirs() }
    private val answers = LinkedBlockingQueue<Pair<PdfEntry, Bitmap?>>()
    private val loader = ThumbnailLoader(ThumbnailCache(File(dir, "cache"))) { pdf, bmp -> answers += pdf to bmp }

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
}
