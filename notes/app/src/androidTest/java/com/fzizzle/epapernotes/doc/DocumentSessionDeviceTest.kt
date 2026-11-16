package com.fzizzle.epapernotes.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.pdf.PdfFile
import com.fzizzle.epapernotes.pdf.PdfOpenException
import com.fzizzle.epapernotes.pdf.TestPdfs
import java.io.File
import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The session with the real PDF code, the real worker thread and real disk syncs. */
@RunWith(AndroidJUnit4::class)
class DocumentSessionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "session-test").apply { deleteRecursively(); mkdirs() }
    private val journals = File(dir, "journals")
    private val line = Stroke(StrokeKind.PEN, (0..40).map { StrokePoint(100f + it * 5, 300f, 0.6f) })

    /** [inMemory]: open as the writing screen does, the whole file read into memory. */
    private class Run(pdf: File, journals: File, inMemory: Boolean = false) {
        val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
        val scheduler = WorkerScheduler()
        var opened: PdfFile? = null
        val session = DocumentSession(pdf, journals, { PdfFile.open(it, inMemory).also { f -> opened = f } }, scheduler, { events += it })

        fun waitFor(timeoutMs: Long = 6000, check: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (!check()) {
                if (System.currentTimeMillis() > end) throw AssertionError("timed out; events: $events")
                Thread.sleep(50)
            }
        }
    }

    private fun strokesIn(pdf: File) = PdfFile.open(pdf).use { p -> (0 until p.pageCount).sumOf { p.items(it).size } }

    @Test fun strokeSurvivesKillBeforeSave() {
        val pdf = TestPdfs.blank(dir)
        val first = Run(pdf, journals).apply { session.start() }
        first.session.addStroke(0, line)
        // The app is killed: no save, no close. Queued work is dropped.
        first.scheduler.killNow()
        Thread.sleep(200)
        first.opened?.close()
        assertEquals(0, strokesIn(pdf))

        val second = Run(pdf, journals).apply { session.start() }
        // The killed session never closed, so the new one first waits out its turn (15 s).
        second.waitFor(timeoutMs = 25_000) { second.events.contains(SessionEvent.Replayed(1)) }
        second.session.saveNow()
        second.waitFor { second.events.contains(SessionEvent.Saved) }
        second.session.close()
        Thread.sleep(200)
        assertEquals(1, strokesIn(pdf))
    }

    /** A saved stroke is erased, then the app is killed: the erase is replayed from the journal by item id. */
    @Test fun eraseSurvivesKillBeforeSave() {
        val pdf = TestPdfs.blank(dir)
        val first = Run(pdf, journals).apply { session.start() }
        first.session.addStroke(0, line)
        first.session.saveNow()
        first.waitFor { first.events.contains(SessionEvent.Saved) }
        assertEquals(1, strokesIn(pdf))

        first.session.erase(0, listOf(Pt(200f, 290f), Pt(200f, 310f)))
        val journal = ChangeJournal.forDocument(journals, pdf)
        first.waitFor { journal.read()!!.changes.singleOrNull() is RemoveItems }
        // The app is killed: no save, no close.
        first.scheduler.killNow()
        Thread.sleep(200)
        first.opened?.close()
        assertEquals(1, strokesIn(pdf))

        val second = Run(pdf, journals).apply { session.start() }
        // The killed session never closed, so the new one first waits out its turn (15 s).
        second.waitFor(timeoutMs = 25_000) { second.events.contains(SessionEvent.Replayed(1)) }
        second.session.saveNow()
        second.waitFor { second.events.contains(SessionEvent.Saved) }
        second.session.close()
        Thread.sleep(200)
        assertEquals(0, strokesIn(pdf))
    }

    @Test fun autosaveWritesTheFile() {
        val pdf = TestPdfs.blank(dir)
        val run = Run(pdf, journals).apply { session.start() }
        run.session.addStroke(0, line)
        // The autosave itself, with nothing asking for a save: its wait, and time to write.
        run.waitFor(timeoutMs = DocumentSession.AUTOSAVE_DELAY_MS + 6000) { run.events.contains(SessionEvent.Saved) }
        run.session.close()
        Thread.sleep(200)
        assertEquals(1, strokesIn(pdf))
        assertEquals(0, ChangeJournal.forDocument(journals, pdf).read()!!.changes.size)
    }

    @Test fun repairedFileIsNotWrittenWithoutDecision() {
        val pdf = TestPdfs.brokenXref(dir)
        val repaired = try { PdfFile.open(pdf).use { it.wasRepaired } } catch (e: PdfOpenException) { false }
        assertTrue("the test file should open as repaired", repaired)
        val before = pdf.readBytes()

        val run = Run(pdf, journals).apply { session.start() }
        run.waitFor { run.events.contains(SessionEvent.NeedsRepairDecision) }
        run.session.addStroke(0, line)
        run.session.saveNow()
        Thread.sleep(1000)
        assertArrayEquals(before, pdf.readBytes())
        assertTrue(run.events.none { it == SessionEvent.Saved })
        run.scheduler.killNow()
        run.opened?.close()
    }

    /**
     * Final review 4: "save my writing to a copy" on a really damaged file. The copy is checked
     * before it's trusted, and refused if it needs repair, so the copy MuPDF writes from a
     * repaired file must open clean, with the same pages. Opened by path and, as the writing
     * screen does it, from memory.
     */
    @Test fun aDamagedFilesCopyOpensCleanWithTheSamePages() {
        for (inMemory in listOf(false, true)) {
            val pdf = TestPdfs.brokenXref(File(dir, "inMemory=$inMemory").apply { mkdirs() })
            val before = pdf.readBytes()
            val run = Run(pdf, journals, inMemory).apply { session.start() }
            run.waitFor { run.events.contains(SessionEvent.NeedsRepairDecision) }
            run.session.addStroke(0, line)
            run.session.saveRepairedToCopy()
            run.waitFor { copyMade(run) != null || run.events.any { it is SessionEvent.SaveFailed } }
            assertTrue("inMemory=$inMemory: ${run.events}", run.events.none { it is SessionEvent.SaveFailed })
            val made = copyMade(run)!!
            assertEquals(CopyReason.DAMAGED, made.why)
            run.session.close()
            Thread.sleep(200)
            PdfFile.open(made.copy).use {
                assertFalse("inMemory=$inMemory: the copy needed repair", it.wasRepaired)
                assertEquals(2, it.pageCount)
            }
            assertEquals(1, strokesIn(made.copy))
            assertArrayEquals(before, pdf.readBytes())
        }
    }

    /** As a laptop or a sync app would: the file gets a new version, with a stroke of theirs. */
    private fun changeElsewhere(pdf: File) {
        val theirs = Stroke(StrokeKind.PEN, (0..40).map { StrokePoint(100f + it * 5, 500f, 0.6f) })
        val next = File(dir, "theirs.tmp")
        PdfFile.open(pdf).use {
            it.apply(AddItems(0, listOf(InkItem("theirs", theirs))))
            it.saveTo(next)
        }
        next.copyTo(pdf, overwrite = true)
        next.delete()
    }

    private fun copyMade(run: Run) = run.events.filterIsInstance<SessionEvent.SavedToCopy>().singleOrNull()

    /** Spec §7.4 with the real PDF code: the changed file is left as it is, and the copy is the old version with the writing. */
    @Test fun aFileChangedWhileOpenIsKeptAndTheWritingGoesIntoACopy() {
        val pdf = TestPdfs.blank(dir)
        val run = Run(pdf, journals).apply { session.start() }
        run.session.addStroke(0, line)
        changeElsewhere(pdf)
        val theirVersion = pdf.readBytes()
        run.session.saveNow()
        run.waitFor { copyMade(run) != null }
        val made = copyMade(run)!!
        assertEquals(CopyReason.CHANGED_WHILE_OPEN, made.why)
        // More writing follows into the copy.
        run.session.addStroke(1, line)
        run.session.saveNow()
        run.waitFor { run.events.contains(SessionEvent.Saved) }
        run.session.close()
        Thread.sleep(200)
        assertArrayEquals(theirVersion, pdf.readBytes())
        assertEquals(2, strokesIn(made.copy)) // the two written here; "theirs" is in the other file
        assertEquals(1, strokesIn(pdf))
    }

    /** Spec §7.4 with the real PDF code: killed with unsaved writing, and the file changed before the next opening. */
    @Test fun writingFromBeforeAKillIsRescuedIntoACopyOfTheChangedFile() {
        val pdf = TestPdfs.blank(dir)
        val first = Run(pdf, journals).apply { session.start() }
        first.session.addStroke(0, line)
        first.scheduler.killNow()
        Thread.sleep(200)
        first.opened?.close()
        changeElsewhere(pdf)
        val theirVersion = pdf.readBytes()

        val second = Run(pdf, journals).apply { session.start() }
        // The killed session never closed, so the new one first waits out its turn (15 s).
        second.waitFor(timeoutMs = 25_000) { copyMade(second) != null }
        val made = copyMade(second)!!
        assertEquals(CopyReason.RESCUED, made.why)
        assertEquals(0, made.notPlaced)
        second.session.close()
        Thread.sleep(200)
        assertArrayEquals(theirVersion, pdf.readBytes())
        assertEquals(2, strokesIn(made.copy)) // theirs, and the rescued one
        assertTrue(second.events.none { it is SessionEvent.EarlierWritingKept })
    }
}
