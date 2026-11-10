package com.fzizzle.epapernotes.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.pdf.TestPdfs
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderWatchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scratch: File
    private val calls = AtomicInteger()
    private val watch = FolderWatch { calls.incrementAndGet() }

    @Before fun setUp() {
        scratch = File(context.getExternalFilesDir(null), "folderwatch-test").apply {
            deleteRecursively()
            mkdirs()
        }
        watch.watch(scratch)
    }

    @After fun tearDown() {
        watch.stop()
        scratch.deleteRecursively()
    }

    private fun addPdf(name: String, pages: Int = 1): File {
        val made = TestPdfs.blank(context.cacheDir, pages = pages)
        return File(scratch, name).also { made.copyTo(it, overwrite = true); made.delete() }
    }

    @Test fun newPdfIsNoticed() {
        addPdf("a.pdf")
        Thread.sleep(1000)
        assertEquals(1, calls.get())
    }

    @Test fun burstOfEventsCallsOnce() {
        repeat(5) { addPdf("p$it.pdf") }
        Thread.sleep(1200)
        assertEquals(1, calls.get())
    }

    @Test fun tempFilesAreIgnored() {
        File(scratch, ".a.pdf.tmp").writeText("x")
        File(scratch, ".hidden").writeText("x")
        Thread.sleep(1000)
        assertEquals(0, calls.get())
    }

    @Test fun changedFileGetsANewThumbnail() {
        val file = addPdf("a.pdf", pages = 1)
        file.setLastModified(1_000_000_000_000L)
        Thread.sleep(1000)
        val cache = ThumbnailCache(File(context.cacheDir, "folderwatch-thumbs"))
        val before = FolderListing.list(scratch).filterIsInstance<PdfEntry>().single()
        calls.set(0)
        addPdf("a.pdf", pages = 3)
        file.setLastModified(1_000_000_005_000L)
        Thread.sleep(1000)
        assertEquals(1, calls.get())
        val after = FolderListing.list(scratch).filterIsInstance<PdfEntry>().single()
        assertNotEquals(cache.fileFor(before).name, cache.fileFor(after).name)
    }

    // Design-gaps Task 2 (spec §6.1, review 2.10).

    /** The library starts the watch before it reads the folder: a file landing meanwhile still re-lists. */
    @Test fun aFileArrivingDuringTheListingIsSeen() {
        // setUp started the watch; the "listing" is this read, with a file arriving during it.
        FolderListing.list(scratch)
        addPdf("late.pdf")
        Thread.sleep(1000)
        assertEquals(1, calls.get())
    }

    /** A sync app deletes and recreates the folder: the watch says so, and watching again sees new files. */
    @Test fun aRecreatedFolderIsWatchedAgain() {
        scratch.deleteRecursively()
        scratch.mkdirs()
        Thread.sleep(1000)
        assertTrue(watch.needsRewatch)
        assertFalse(watch.needsRewatch) // read once
        watch.watch(scratch)
        calls.set(0)
        addPdf("a.pdf")
        Thread.sleep(1000)
        assertEquals(1, calls.get())
    }

    /** A subfolder's tile shows how many PDFs it has: a PDF added inside it re-lists too. */
    @Test fun aFileAddedInsideASubfolderIsSeen() {
        val week = File(scratch, "Week 2").apply { mkdirs() }
        Thread.sleep(1000)
        calls.set(0)
        watch.watchChildren(listOf(week))
        TestPdfs.blank(context.cacheDir).let { it.copyTo(File(week, "b.pdf")); it.delete() }
        Thread.sleep(1000)
        assertEquals(1, calls.get())
    }

    @Test fun watchingChildrenTwiceKeepsOneObserverEach() {
        val week = File(scratch, "Week 2").apply { mkdirs() }
        Thread.sleep(1000)
        calls.set(0)
        watch.watchChildren(listOf(week))
        watch.watchChildren(listOf(week))
        File(week, "c.pdf").writeText("x")
        Thread.sleep(1200)
        assertEquals(1, calls.get())
        // And a folder no longer passed isn't watched any more.
        watch.watchChildren(emptyList())
        calls.set(0)
        File(week, "d.pdf").writeText("x")
        Thread.sleep(1000)
        assertEquals(0, calls.get())
    }
}
