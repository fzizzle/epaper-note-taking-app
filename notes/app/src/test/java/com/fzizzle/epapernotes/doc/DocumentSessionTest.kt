package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.storage.FileIdentity
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var pdf: File
    private lateinit var journals: File
    private val scheduler = FakeScheduler()
    private val events = mutableListOf<SessionEvent>()
    private var fake = FakePdfPages()
    private val dot = Stroke(StrokeKind.PEN, listOf(StrokePoint(1f, 2f, 0.5f)))

    @Before fun setUp() {
        pdf = tmp.newFile("Lecture.pdf").apply { writeText("original") }
        journals = tmp.newFolder("journals")
    }

    private fun session() = DocumentSession(
        pdf = pdf,
        journalDir = journals,
        open = { fake },
        scheduler = scheduler,
        events = { events += it },
        clock = { LocalDateTime.of(2026, 11, 9, 14, 5) },
    ).also { it.start() }

    private fun journalStrokes(file: File = pdf) = StrokeJournal.forDocument(journals, file).read()!!.strokes.size

    @Test fun strokeIsJournaledBeforeAddStrokeReturns() {
        val s = session()
        s.addStroke(0, dot)
        assertEquals(1, journalStrokes())
    }

    /** Review C1: only this file's own leftover temp is removed; another file's may be mid-save. */
    @Test fun startOnlyCleansItsOwnTempFile() {
        val mine = File(pdf.parentFile, ".Lecture.pdf.pnotes-tmp").apply { writeText("x") }
        val other = File(pdf.parentFile, ".Other.pdf.pnotes-tmp").apply { writeText("x") }
        session()
        assertFalse(mine.exists())
        assertTrue(other.exists())
    }

    @Test fun autosaveRunsAfterDelayAndClearsJournal() {
        val s = session()
        s.addStroke(0, dot)
        assertEquals(0, fake.saves)
        scheduler.runDelayed()
        assertEquals("strokes=1", pdf.readText())
        assertEquals(0, journalStrokes())
        assertTrue(events.contains(SessionEvent.Saved))
    }

    @Test fun newStrokeReschedulesAutosave() {
        val s = session()
        s.addStroke(0, dot)
        s.addStroke(0, dot)
        assertEquals(1, scheduler.delayed.size)
    }

    @Test fun saveNowSavesImmediately() {
        val s = session()
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("strokes=1", pdf.readText())
        assertTrue(scheduler.delayed.isEmpty())
    }

    @Test fun nothingUnsavedMeansNoSave() {
        session().saveNow()
        assertEquals(0, fake.saves)
        assertEquals("original", pdf.readText())
    }

    @Test fun changedOnDiskBlocksSaveAndKeepsJournal() {
        val s = session()
        pdf.writeText("edited on the laptop")
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("edited on the laptop", pdf.readText())
        assertTrue(events.contains(SessionEvent.ChangedOnDisk))
        assertEquals(1, journalStrokes())
    }

    @Test fun failedSaveKeepsJournalAndRetries() {
        val s = session()
        s.addStroke(0, dot)
        fake.failNextSave = true
        s.saveNow()
        assertTrue(events.any { it is SessionEvent.SaveFailed })
        assertEquals("original", pdf.readText())
        assertEquals(1, journalStrokes())
        s.saveNow()
        assertEquals("strokes=1", pdf.readText())
        assertEquals(0, journalStrokes())
    }

    /** A stroke that arrives while a save is running isn't in that save. It must stay in the journal. */
    @Test fun strokeArrivingDuringSaveStaysJournaled() {
        val s = session()
        s.addStroke(0, dot)
        fake.duringSave = { fake.duringSave = null; s.addStroke(1, dot) }
        s.saveNow()
        assertEquals("strokes=1", pdf.readText()) // the save had only the first stroke
        assertEquals(1, journalStrokes()) // the second is still protected
    }

    @Test fun matchingJournalIsReplayedOnStart() {
        StrokeJournal.forDocument(journals, pdf).apply { start(FileIdentity.of(pdf)); append(0, dot); append(2, dot) }
        session()
        assertTrue(events.contains(SessionEvent.Replayed(2)))
        assertEquals(2, fake.strokes.size)
    }

    @Test fun mismatchedJournalIsKeptNotReplayed() {
        StrokeJournal.forDocument(journals, pdf).apply { start(FileIdentity(1L, 1L, "ef".repeat(32))); append(0, dot) }
        session()
        assertEquals(0, fake.strokes.size)
        val kept = events.filterIsInstance<SessionEvent.EarlierWritingKept>().single().journal
        assertEquals(1, StrokeJournal(kept).read()!!.strokes.size)
    }

    @Test fun repairedFileHoldsSavesUntilDecision() {
        fake = FakePdfPages(wasRepaired = true)
        val s = session()
        assertTrue(events.contains(SessionEvent.NeedsRepairDecision))
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals(0, fake.saves)
        s.saveIntoRepairedFile()
        assertEquals("strokes=1", pdf.readText())
    }

    @Test fun repairedToCopyLeavesOriginalAndContinuesInCopy() {
        fake = FakePdfPages(wasRepaired = true)
        val s = session()
        s.addStroke(0, dot)
        s.saveRepairedToCopy()
        val copy = File(pdf.parentFile, "Lecture (my edits 2026-11-09).pdf")
        assertEquals("original", pdf.readText())
        assertEquals("strokes=1", copy.readText())
        assertTrue(events.contains(SessionEvent.SavedToCopy(copy)))
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("strokes=2", copy.readText())
        assertEquals("original", pdf.readText())
        assertEquals(0, journalStrokes(copy))
    }

    @Test fun closeSavesAndClosesThePdf() {
        val s = session()
        s.addStroke(0, dot)
        s.close()
        assertEquals("strokes=1", pdf.readText())
        assertTrue(fake.closed)
    }

    /** The screen draws pages through the session, so MuPDF is only ever used on the worker thread. */
    @Test fun runOnDocumentGetsTheOpenPdf() {
        val s = session()
        var seen: PdfPages? = null
        s.runOnDocument { seen = it }
        assertTrue(seen === fake)
    }

    /** Review I2: a file a sync app deleted must be reported, not crash the worker silently. */
    @Test fun deletedFileIsReportedNotThrown() {
        val s = session()
        pdf.delete()
        s.addStroke(0, dot)
        s.saveNow()
        assertTrue(events.contains(SessionEvent.ChangedOnDisk))
        assertEquals(1, journalStrokes())
    }

    /** Review I5: MuPDF can repair a file later, while reading it. Ask before saving even then. */
    @Test fun repairFoundLaterStillAsksFirst() {
        val s = session()
        s.addStroke(0, dot)
        fake.wasRepaired = true
        s.saveNow()
        assertTrue(events.contains(SessionEvent.NeedsRepairDecision))
        assertEquals("original", pdf.readText())
    }

    /**
     * Review C2: turning the tablet closes the writing screen and opens a new one on the same
     * file. The new session must wait until the old one has finished saving, or it sees its own
     * save as "changed elsewhere" and stops saving.
     */
    @Test fun secondSessionWaitsForFirstToFinishSaving() {
        val firstWorker = WorkerScheduler("first")
        val first = DocumentSession(pdf, journals, { FakePdfPages(saveDelayMs = 400) }, firstWorker, {})
        first.start()
        first.addStroke(0, dot)
        first.close()

        val secondEvents = java.util.Collections.synchronizedList(mutableListOf<SessionEvent>())
        val secondWorker = WorkerScheduler("second")
        val second = DocumentSession(pdf, journals, { FakePdfPages() }, secondWorker, { secondEvents += it })
        second.start()
        assertTrue(firstWorker.awaitTermination(5000)) // the old screen's save lands after the new one opened
        second.addStroke(0, dot)
        second.saveNow()
        second.close()
        assertTrue(secondWorker.awaitTermination(5000))

        assertFalse("events: $secondEvents", secondEvents.contains(SessionEvent.ChangedOnDisk))
        assertTrue(secondEvents.contains(SessionEvent.Saved))
        assertEquals(0, journalStrokes())
    }
}
