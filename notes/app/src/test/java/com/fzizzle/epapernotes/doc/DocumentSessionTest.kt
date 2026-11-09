package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.storage.FileIdentity
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    /** A line across y=100, from x=10 to x=50, far from [dot]. */
    private val line = Stroke(StrokeKind.PEN, (0..8).map { StrokePoint(10f + it * 5, 100f, 0.5f) })
    /** An eraser swipe down across [line]. */
    private val acrossLine = listOf(Pt(30f, 90f), Pt(30f, 110f))

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
        // These tests "kill" a session by just starting another; don't wait out the dead one's turn.
        previousSessionWaitMs = 0,
    ).also { it.start() }

    private fun journalStrokes(file: File = pdf) = journalChanges(file).size

    private fun journalChanges(file: File = pdf) = ChangeJournal.forDocument(journals, file).read()!!.changes

    private fun strokesOn(page: Int) = fake.items(page).map { (it as InkItem).stroke }

    private fun lastHistory() = events.filterIsInstance<SessionEvent.HistoryChanged>().last()

    /** The app is killed before the autosave: a new session on the same files, with a fresh PDF fake. */
    private fun reopenAfterKill(): DocumentSession {
        events.clear()
        fake = FakePdfPages()
        return session()
    }

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
        assertEquals("items=1", pdf.readText())
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
        assertEquals("items=1", pdf.readText())
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
        assertEquals("items=1", pdf.readText())
        assertEquals(0, journalStrokes())
    }

    /** A stroke that arrives while a save is running isn't in that save. It must stay in the journal. */
    @Test fun strokeArrivingDuringSaveStaysJournaled() {
        val s = session()
        s.addStroke(0, dot)
        fake.duringSave = { fake.duringSave = null; s.addStroke(1, dot) }
        s.saveNow()
        assertEquals("items=1", pdf.readText()) // the save had only the first stroke
        assertEquals(1, journalStrokes()) // the second is still protected
    }

    @Test fun matchingJournalIsReplayedOnStart() {
        ChangeJournal.forDocument(journals, pdf).apply { start(FileIdentity.of(pdf)); append(AddItems(0, listOf(InkItem("a", dot)))); append(AddItems(2, listOf(InkItem("b", dot)))) }
        session()
        assertTrue(events.contains(SessionEvent.Replayed(2)))
        assertEquals(2, fake.itemCount)
    }

    @Test fun mismatchedJournalIsKeptNotReplayed() {
        ChangeJournal.forDocument(journals, pdf).apply { start(FileIdentity(1L, 1L, "ef".repeat(32))); append(AddItems(0, listOf(InkItem("a", dot)))) }
        session()
        assertEquals(0, fake.itemCount)
        val kept = events.filterIsInstance<SessionEvent.EarlierWritingKept>().single().journal
        assertEquals(1, ChangeJournal(kept).read()!!.changes.size)
    }

    @Test fun repairedFileHoldsSavesUntilDecision() {
        fake = FakePdfPages(wasRepaired = true)
        val s = session()
        assertTrue(events.contains(SessionEvent.NeedsRepairDecision))
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals(0, fake.saves)
        s.saveIntoRepairedFile()
        assertEquals("items=1", pdf.readText())
    }

    @Test fun repairedToCopyLeavesOriginalAndContinuesInCopy() {
        fake = FakePdfPages(wasRepaired = true)
        val s = session()
        s.addStroke(0, dot)
        s.saveRepairedToCopy()
        val copy = File(pdf.parentFile, "Lecture (my edits 2026-11-09).pdf")
        assertEquals("original", pdf.readText())
        assertEquals("items=1", copy.readText())
        assertTrue(events.contains(SessionEvent.SavedToCopy(copy)))
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("items=2", copy.readText())
        assertEquals("original", pdf.readText())
        assertEquals(0, journalStrokes(copy))
    }

    @Test fun closeSavesAndClosesThePdf() {
        val s = session()
        s.addStroke(0, dot)
        s.close()
        assertEquals("items=1", pdf.readText())
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

    /** A plan-2 journal is replayed, and strokes added after that must not be lost on the next open. */
    @Test fun plan2JournalIsUpgradedSoNewStrokesSurvive() {
        val journalFile = ChangeJournal.forDocument(journals, pdf).file
        journalFile.writeText("pnj1 ${FileIdentity.of(pdf).encode()}\nadd 0 pn1 pen 1.00,2.00,0.500\n")
        val first = session()
        assertTrue(events.contains(SessionEvent.Replayed(1)))
        first.addStroke(1, dot)

        events.clear()
        fake = FakePdfPages()
        session() // no autosave has run
        assertTrue(events.contains(SessionEvent.Replayed(2)))
        assertEquals(2, fake.itemCount)
    }

    @Test fun eraseRemovesTouchedStrokeAndIsJournaled() {
        val s = session()
        s.addStroke(0, line)
        s.addStroke(0, dot)
        s.erase(0, acrossLine)
        assertEquals(listOf(dot), strokesOn(0))
        val removed = journalChanges().last() as RemoveItems
        assertEquals(listOf(line), removed.items.map { (it as InkItem).stroke })
        assertTrue(events.contains(SessionEvent.PageChanged(setOf(0), rotated = false)))
    }

    @Test fun eraseThatTouchesNothingRecordsNothing() {
        val s = session()
        s.addStroke(0, dot)
        s.erase(0, acrossLine)
        assertEquals(1, journalStrokes())
    }

    /** The pen writes while the worker is working out an erase. Both reach the save, in order. */
    @Test fun strokeDuringEraseIsSavedWithIt() {
        val s = session()
        s.addStroke(0, line)
        fake.duringItems = { fake.duringItems = null; s.addStroke(0, dot) }
        s.erase(0, acrossLine)
        scheduler.runDelayed()
        assertEquals(listOf(dot), strokesOn(0))
        assertEquals("items=1", pdf.readText())
        assertEquals(0, journalStrokes())
    }

    /** A second erase queued behind the first must see the first's result, not remove the stroke again. */
    @Test fun twoErasesInARowRemoveOnce() {
        val s = session()
        s.addStroke(0, line)
        fake.duringItems = { fake.duringItems = null; s.erase(0, acrossLine) }
        s.erase(0, acrossLine)
        assertEquals(2, journalStrokes()) // the stroke and one erase
        s.undo() // the erase: the stroke is back
        s.undo() // the stroke itself
        assertEquals(0, fake.itemCount)
    }

    @Test fun eraseSurvivesKillBeforeSave() {
        val s = session()
        s.addStroke(0, line)
        s.addStroke(0, dot)
        s.erase(0, acrossLine)
        val before = fake.items(0)
        reopenAfterKill()
        assertEquals(before, fake.items(0))
    }

    @Test fun moveSurvivesKillBeforeSave() {
        val s = session()
        s.addStroke(0, line)
        val item = fake.items(0).single() as InkItem
        val moved = item.copy(stroke = item.stroke.copy(points = item.stroke.points.map { it.copy(y = it.y + 50) }))
        s.record(ReplaceItems(0, listOf(item), listOf(moved)))
        assertEquals(listOf<Item>(moved), fake.items(0))
        reopenAfterKill()
        assertEquals(listOf<Item>(moved), fake.items(0))
    }

    @Test fun undoRemovesTheLastStroke() {
        val s = session()
        s.addStroke(0, dot)
        s.addStroke(0, line)
        s.undo()
        assertEquals(listOf(dot), strokesOn(0))
        assertEquals(3, journalStrokes()) // the undo is a change too
    }

    @Test fun redoPutsItBack() {
        val s = session()
        s.addStroke(0, dot)
        s.undo()
        s.redo()
        assertEquals(listOf(dot), strokesOn(0))
    }

    @Test fun newChangeClearsRedo() {
        val s = session()
        s.addStroke(0, dot)
        s.addStroke(0, line)
        s.undo()
        s.addStroke(1, dot)
        s.redo()
        assertEquals(listOf(dot), strokesOn(0))
        assertEquals(listOf(dot), strokesOn(1))
        assertFalse(lastHistory().canRedo)
    }

    @Test fun undoAfterSaveIsSavedToo() {
        val s = session()
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("items=1", pdf.readText())
        s.undo()
        s.saveNow()
        assertEquals("items=0", pdf.readText())
        assertEquals(0, journalStrokes())
    }

    @Test fun undoSurvivesKillBeforeSave() {
        val s = session()
        s.addStroke(0, dot)
        s.addStroke(0, line)
        s.undo()
        reopenAfterKill()
        assertEquals(listOf(dot), strokesOn(0))
    }

    @Test fun historyStopsAt200() {
        val s = session()
        repeat(DocumentSession.HISTORY_LIMIT + 1) { s.addStroke(0, dot) }
        repeat(DocumentSession.HISTORY_LIMIT + 1) { s.undo() }
        assertEquals(1, fake.itemCount) // the oldest stroke is beyond undo
        assertEquals(SessionEvent.HistoryChanged(canUndo = false, canRedo = true), lastHistory())
    }

    @Test fun rotateTurnsEachPageBy90() {
        val s = session()
        fake.rotations[1] = 90
        fake.rotations[2] = 270
        s.rotate(listOf(0, 1, 2))
        assertEquals(mapOf(0 to 90, 1 to 180, 2 to 0), fake.rotations)
        assertEquals(RotatePages(mapOf(0 to 0, 1 to 90, 2 to 270), mapOf(0 to 90, 1 to 180, 2 to 0)), journalChanges().single())
        assertTrue(events.contains(SessionEvent.PageChanged(setOf(0, 1, 2), rotated = true)))
    }

    @Test fun rotateAllIsOneUndoStep() {
        val s = session()
        s.rotate((0 until fake.pageCount).toList())
        s.undo()
        assertTrue((0 until fake.pageCount).all { fake.rotation(it) == 0 })
        assertEquals(SessionEvent.HistoryChanged(canUndo = false, canRedo = true), lastHistory())
    }

    @Test fun emptyChangeDoesNothing() {
        val s = session()
        s.record(AddItems(0, emptyList()))
        s.record(RotatePages(mapOf(0 to 90), mapOf(0 to 90)))
        assertEquals(0, journalStrokes())
        assertTrue(events.none { it is SessionEvent.HistoryChanged })
    }

    @Test fun historyChangedTellsTheStrip() {
        val s = session()
        s.addStroke(0, dot)
        assertEquals(SessionEvent.HistoryChanged(canUndo = true, canRedo = false), lastHistory())
        s.undo()
        assertEquals(SessionEvent.HistoryChanged(canUndo = false, canRedo = true), lastHistory())
        s.redo()
        assertEquals(SessionEvent.HistoryChanged(canUndo = true, canRedo = false), lastHistory())
    }

    /** A plan-2 stroke goes by its object number in this file only; moved, it needs an id of its own. */
    @Test fun oldIdsGetFreshOnesOnMove() {
        val s = session()
        val old = InkItem("o7", line)
        fake.pageItems[0] = mutableListOf(old)
        s.record(ReplaceItems(0, listOf(old), listOf(old.copy(stroke = dot))))
        val journaled = journalChanges().single() as ReplaceItems
        assertEquals("o7", journaled.before.single().id)
        val fresh = journaled.after.single().id
        assertTrue(fresh, fresh.matches(Regex("[0-9a-f]{12}")))
        assertEquals(listOf(fresh), fake.items(0).map { it.id })
        s.undo() // the undo refers to the id now in the file
        assertEquals(listOf<Item>(old), fake.items(0))
    }

    @Test fun replaysAPlan2Journal() {
        val journalFile = ChangeJournal.forDocument(journals, pdf).file
        journalFile.writeText("pnj1 ${FileIdentity.of(pdf).encode()}\nadd 0 pn1 pen 1.00,2.00,0.500\n")
        val s = session()
        assertTrue(events.contains(SessionEvent.Replayed(1)))
        assertEquals(1, fake.itemCount)
        s.undo() // a replayed change belongs to an earlier session: nothing to undo
        assertEquals(1, fake.itemCount)
    }

    @Test fun selectAnswersWithTheLoopedItems() {
        val s = session()
        s.addStroke(0, line)
        s.addStroke(0, dot)
        var picked: List<Item>? = null
        s.select(0, listOf(Pt(0f, 80f), Pt(60f, 80f), Pt(60f, 120f), Pt(0f, 120f))) { picked = it }
        assertEquals(listOf(line), picked!!.map { (it as InkItem).stroke })
        var all: List<Item>? = null
        s.items(0) { all = it }
        assertEquals(2, all!!.size)
        assertNotEquals(all!![0].id, all!![1].id)
    }

    /**
     * The ordering rule on real threads: the pen writes, another thread draws and erases lines,
     * and saves run in between. Every save must contain exactly the changes it counts.
     */
    @Test fun strokesErasesAndSavesAtOnceLoseNothing() {
        val worker = WorkerScheduler("busy")
        val pdfFake = FakePdfPages(saveDelayMs = 1)
        val s = DocumentSession(pdf, journals, { pdfFake }, worker, {})
        s.start()
        val pen = Thread { repeat(300) { s.addStroke(0, dot); if (it % 25 == 0) s.saveNow() } }
        val other = Thread { repeat(50) { s.addStroke(0, line); s.erase(0, acrossLine); if (it % 10 == 0) s.saveNow() } }
        pen.start(); other.start(); pen.join(); other.join()
        s.close()
        assertTrue(worker.awaitTermination(10_000))
        assertEquals(List(300) { dot }, pdfFake.items(0).map { (it as InkItem).stroke })
        assertEquals("items=300", pdf.readText())
        assertEquals(0, journalStrokes())
    }

    /** Review: the selection keeps the moved items, so it must get the ids the file really has. */
    @Test fun secondMoveOfPlan2StrokeDoesNotDuplicate() {
        val s = session()
        val old = InkItem("o7", line)
        fake.pageItems[0] = mutableListOf(old)
        val first = s.record(ReplaceItems(0, listOf(old), listOf(old.copy(stroke = dot)))) as ReplaceItems
        val moved = first.after.single()
        assertNotEquals("o7", moved.id)
        s.record(ReplaceItems(0, listOf(moved), listOf((moved as InkItem).copy(stroke = line))))
        assertEquals(listOf(line), strokesOn(0))
    }

    @Test fun zeroDistanceMoveIsNoUndoStep() {
        val s = session()
        s.addStroke(0, dot)
        val item = fake.items(0).single()
        assertEquals(null, s.record(ReplaceItems(0, listOf(item), listOf(item))))
        assertEquals(1, journalStrokes())
        s.undo()
        assertEquals(0, fake.itemCount) // the undo took back the stroke, not a zero move
    }

    @Test fun failedApplyStopsSavingAndKeepsTheJournal() {
        val s = session()
        s.addStroke(0, dot)
        fake.failNextApply = true
        s.addStroke(0, line)
        assertTrue(events.contains(SessionEvent.WritingStopped))
        s.saveNow()
        assertEquals(0, fake.saves)
        assertEquals(2, journalStrokes())
        reopenAfterKill()
        assertEquals(listOf(dot, line), strokesOn(0))
    }

    @Test fun failedApplyIsNotSavedIntoARepairedFileOrItsCopy() {
        fake = FakePdfPages(wasRepaired = true)
        val s = session()
        fake.failNextApply = true
        s.addStroke(0, dot)
        events.clear()
        s.saveRepairedToCopy()
        assertFalse(File(pdf.parentFile, "Lecture (my edits 2026-11-09).pdf").exists())
        assertEquals(listOf<SessionEvent>(SessionEvent.WritingStopped), events)
        events.clear()
        s.saveIntoRepairedFile()
        assertEquals("original", pdf.readText())
        assertEquals(listOf<SessionEvent>(SessionEvent.WritingStopped), events)
    }

    @Test fun replayThatFailsToApplyIsNotReportedAsRecovered() {
        ChangeJournal.forDocument(journals, pdf).apply { start(FileIdentity.of(pdf)); append(AddItems(0, listOf(InkItem("a", dot)))) }
        fake.failNextApply = true
        session()
        assertFalse(events.any { it is SessionEvent.Replayed })
        assertTrue(events.contains(SessionEvent.WritingStopped))
        assertEquals(1, journalStrokes())
    }

    /** A bad page number from a tool is logged, never thrown out of the worker. */
    @Test fun workerToolFailuresAreCaught() {
        val s = session()
        s.erase(99, acrossLine)
        s.rotate(listOf(99))
        var answered: List<Item>? = null
        s.select(99, listOf(Pt(0f, 0f), Pt(9f, 0f), Pt(9f, 9f))) { answered = it }
        assertEquals(emptyList<Item>(), answered)
        answered = null
        s.items(99) { answered = it }
        assertEquals(emptyList<Item>(), answered)
        s.addStroke(0, dot) // still working
        s.saveNow()
        assertEquals("items=1", pdf.readText())
    }
}
