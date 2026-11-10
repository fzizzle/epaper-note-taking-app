package com.fzizzle.epapernotes.storage

import android.os.Environment
import android.os.storage.StorageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.ItemIds
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.library.FileOps
import com.fzizzle.epapernotes.library.FolderListing
import com.fzizzle.epapernotes.library.FolderWatch
import com.fzizzle.epapernotes.library.HookPath
import com.fzizzle.epapernotes.library.Volumes
import com.fzizzle.epapernotes.ui.STORAGE
import com.fzizzle.epapernotes.ui.StorageVolumes
import com.fzizzle.epapernotes.library.PageMemory
import com.fzizzle.epapernotes.library.PdfEntry
import com.fzizzle.epapernotes.pdf.PdfFile
import com.fzizzle.epapernotes.pdf.TestPdfs
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library folder can be on a memory card (exFAT, through Android's FUSE layer): a save
 * there must still be a temp file, fsync and one atomic rename, and listing, watching and
 * renaming must work as on the tablet's own storage. Skipped when no card is in.
 *
 * Works only in a hidden scratch folder on the card, removed afterwards whatever happens.
 */
@RunWith(AndroidJUnit4::class)
class RemovableVolumeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val line = Stroke(StrokeKind.PEN, (0..50).map { StrokePoint(100f + it * 4, 400f, it / 50f) })
    private lateinit var scratch: File
    private lateinit var journals: File

    @Before fun setUp() {
        assumeTrue("needs All files access", Environment.isExternalStorageManager())
        val card = context.getSystemService(StorageManager::class.java).storageVolumes
            .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null }
            ?.directory
        assumeTrue("no memory card mounted", card != null)
        scratch = File(card, ".pnotes-check-" + UUID.randomUUID().toString().take(8))
        assertTrue("couldn't make ${scratch.path}", scratch.mkdir())
        journals = File(context.cacheDir, "removable-journals").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() {
        if (::scratch.isInitialized) scratch.deleteRecursively()
        if (::journals.isInitialized) journals.deleteRecursively()
    }

    private fun pdfOnCard(name: String): File {
        val made = TestPdfs.blank(context.cacheDir, pages = 1)
        return File(scratch, name).also { made.copyTo(it); made.delete() }
    }

    @Test fun strokeSurvivesASafeSaveOnTheCard() {
        val file = pdfOnCard("Lecture 1.pdf")
        PdfFile.open(file).use { pdf ->
            pdf.apply(AddItems(0, listOf(InkItem(ItemIds.next(), line))))
            SafeSave.replace(file) { pdf.saveTo(it) }
        }
        assertFalse(SafeSave.tempFileFor(file).exists())
        PdfFile.open(file).use { assertEquals(line, it.items(0).filterIsInstance<InkItem>().single().stroke) }
    }

    @Test fun listingSeesPdfsAndHidesTempFiles() {
        pdfOnCard("Lecture 2.pdf")
        pdfOnCard("lecture 10.PDF")
        File(scratch, "Week 1").mkdir()
        File(scratch, ".Lecture 2.pdf.pnotes-tmp").writeText("x")
        val names = FolderListing.list(scratch).map { it.name }
        assertEquals(listOf("Week 1", "Lecture 2", "lecture 10"), names)
    }

    @Test fun watchNoticesANewPdf() {
        val calls = AtomicInteger()
        val watch = FolderWatch { calls.incrementAndGet() }
        watch.watch(scratch)
        try {
            pdfOnCard("a.pdf")
            Thread.sleep(1000)
            assertEquals(1, calls.get())
        } finally {
            watch.stop()
        }
    }

    @Test fun renameCarriesThePage() {
        val pages = HashMap<String, Int>()
        val memory = object : PageMemory {
            override fun move(from: String, to: String) { pages.remove(from)?.let { pages[to] = it } }
            override fun remove(path: String) { pages.remove(path) }
        }
        val ops = FileOps(scratch, journals, memory)
        val file = pdfOnCard("Week 2.pdf")
        pages[file.path] = 3
        assertEquals(FileOps.Result.Done, ops.rename(file, "Week 3"))
        val renamed = File(scratch, "Week 3.pdf")
        assertTrue(renamed.isFile)
        assertFalse(file.exists())
        assertEquals(3, pages[renamed.path])
        // exFAT ignores case: a name differing only in case is the same file, and must not be offered twice.
        assertTrue(File(scratch, "WEEK 3.pdf").exists())
        assertEquals(FileOps.Result.Refused("There's already a 'week 3.pdf' here."), ops.rename(pdfOnCard("x.pdf"), "week 3"))
    }

    /**
     * A name changed only in case ("lecture" to "Lecture"): the card ignores case, and a plain
     * rename there reports success but keeps the old name, so FileOps goes by a hidden name.
     */
    @Test fun renameChangingOnlyCase() {
        val ops = FileOps(scratch, journals, object : PageMemory {
            override fun move(from: String, to: String) {}
            override fun remove(path: String) {}
        })
        pdfOnCard("lecture.pdf")
        assertEquals(FileOps.Result.Done, ops.rename(File(scratch, "lecture.pdf"), "Lecture"))
        assertEquals(listOf("Lecture.pdf"), scratch.list()!!.filter { !it.startsWith(".") })
        assertTrue(FolderListing.list(scratch).single() is PdfEntry)
    }

    @Test fun theCardIsAVolumeAndHookPathsReachIt() {
        val volumes = StorageVolumes.mounted(context)
        assertEquals(STORAGE, volumes.first().directory)
        assertEquals("Tablet storage", volumes.first().label)
        val card = volumes.single { it.directory == scratch.parentFile }
        assertFalse(card.isPrimary)
        val inside = File(scratch, "a.pdf")
        assertEquals(inside, HookPath.under(volumes.map { it.directory }, inside.path))
        assertEquals(card, Volumes.holding(volumes, inside))
    }
}
