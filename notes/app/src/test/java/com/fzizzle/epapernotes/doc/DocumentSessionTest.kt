package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
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
import org.junit.Assume.assumeFalse
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
    /** How the session opens the PDF. A test can make it fail. */
    private var opening: (File) -> PdfPages = { fake }
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
        open = { opening(it) },
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

    /** Owner: strokes saved before the fix are drawn mirrored. Opening redraws them, and that's saved like an edit. */
    @Test fun redrawnInkIsSavedWithNothingJournaled() {
        fake.faultyStrokes += mapOf(1 to 2, 3 to 1)
        session()
        scheduler.runDelayed() // the redraw, a moment after opening
        assertEquals(0, fake.saves)
        scheduler.runDelayed() // the autosave it asked for
        assertEquals(1, fake.saves)
        assertTrue(events.contains(SessionEvent.Saved))
        scheduler.runDelayed()
        assertEquals(1, fake.saves) // nothing left to save
    }

    /** Each redrawn page is announced, so the screen draws it again if it's showing. */
    @Test fun eachRedrawnPageIsAnnounced() {
        fake.faultyStrokes += mapOf(1 to 2, 3 to 1)
        session()
        assertTrue(events.none { it is SessionEvent.InkRedrawn }) // not before the first page is drawn
        scheduler.runDelayed()
        assertEquals(listOf(1, 3), events.filterIsInstance<SessionEvent.InkRedrawn>().map { it.page })
    }

    /**
     * The redraw goes a page at a time, so a page asked for while it runs doesn't wait for the
     * whole file (about 21 ms a stroke; 1.4 s for the owner's test file).
     */
    @Test fun pageRequestsGetInBetweenTheRedraw() {
        val order = mutableListOf<String>()
        fake.duringRedraw = { page ->
            order += "redraw $page"
            // What runOnDocument does when the screen asks for the first page.
            if (page == 0) scheduler.post { order += "draw first page" }
        }
        session()
        scheduler.runDelayed()
        assertEquals(listOf("redraw 0", "draw first page", "redraw 1"), order.take(3))
        assertEquals(5 + 1, order.size) // every page checked once
    }

    @Test fun fileWithNothingToRedrawIsNotSavedJustForOpening() {
        session()
        scheduler.runDelayed()
        scheduler.runDelayed()
        assertEquals(0, fake.saves)
    }

    /** A repaired file waits for the owner's decision before anything is saved into it, the redraw too. */
    @Test fun redrawnInkWaitsForTheRepairDecision() {
        fake = FakePdfPages(wasRepaired = true).apply { faultyStrokes[1] = 1 }
        session()
        scheduler.runDelayed()
        scheduler.runDelayed()
        assertEquals(0, fake.saves)
    }

    /**
     * A page full of old strokes is redrawn a part at a time ([DocumentSession.REDRAW_CHUNK]
     * each), so the pen's changes, the screen's pages, a save or closing get in between; a
     * stroke written meanwhile is applied before the next part and saved with the redraw.
     */
    @Test fun aFullPageIsRedrawnInPartsWithChangesInBetween() {
        fake.faultyStrokes[0] = 60
        val s = session()
        val order = mutableListOf<String>()
        fake.duringRedraw = { page ->
            if (page == 0) {
                order += "redraw part, strokes on page: ${fake.pageItems[0].orEmpty().size}"
                if (order.size == 1) s.addStroke(0, dot) // the pen, while the first part runs
            }
        }
        scheduler.runDelayed()
        assertEquals(
            listOf("redraw part, strokes on page: 0", "redraw part, strokes on page: 1", "redraw part, strokes on page: 1"),
            order, // three worker tasks for page 0 (25 + 25 + 10), the stroke applied between the first two
        )
        assertTrue(fake.faultyStrokes.isEmpty())
        assertEquals(listOf(SessionEvent.InkRedrawn(0)), events.filterIsInstance<SessionEvent.InkRedrawn>()) // once the page is done
        scheduler.runDelayed() // the autosave
        assertEquals(1, fake.saves)
        assertEquals("items=1", pdf.readText())
        assertEquals(0, journalStrokes())
    }

    /** Closing in the middle saves what's redrawn so far; the next opening finds the rest. */
    @Test fun closingMidwaySavesWhatIsRedrawnSoFar() {
        fake.faultyStrokes[0] = 60
        val s = session()
        fake.duringRedraw = { if (fake.faultyStrokes[0] == 35) s.close() } // as the second part starts
        scheduler.runDelayed()
        assertEquals(1, fake.saves)
        assertEquals(10, fake.faultyStrokes[0]) // the part that was running finished; the third never ran
    }

    /** Review: once the copy holds the redrawn strokes, they aren't saved into it a second time. */
    @Test fun redrawnInkSavedToTheCopyIsNotSavedAgain() {
        fake = FakePdfPages(wasRepaired = true).apply { faultyStrokes[1] = 1 }
        val s = session()
        scheduler.runDelayed() // the redraw; its autosave waits for the decision
        s.saveRepairedToCopy()
        assertEquals(1, fake.saves)
        scheduler.runDelayed()
        assertEquals(1, fake.saves)
    }

    @Test fun newStrokeReschedulesAutosave() {
        val s = session()
        s.addStroke(0, dot)
        s.addStroke(0, dot)
        // One autosave, rescheduled; the other wait is the old-ink check a moment after opening.
        assertEquals(setOf("autosave", "redraw-ink"), scheduler.delayed.keys)
    }

    @Test fun saveNowSavesImmediately() {
        val s = session()
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("items=1", pdf.readText())
        assertFalse("autosave" in scheduler.delayed)
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
        var hits = -1
        s.erase(0, acrossLine) { hits = it }
        assertEquals(1, journalStrokes())
        assertEquals(0, hits)
    }

    @Test fun eraseAnswersWithHowManyItemsItRemoved() {
        val s = session()
        s.addStroke(0, line)
        s.addStroke(0, dot)
        var hits = -1
        s.erase(0, acrossLine) { hits = it }
        assertEquals(1, hits)
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

    /** Review: a stale selection moved after its stroke was erased must not bring the stroke back. */
    @Test fun moveOfErasedItemDoesNotBringItBack() {
        val s = session()
        s.addStroke(0, line)
        val item = fake.items(0).single() as InkItem
        s.erase(0, acrossLine)
        assertEquals(0, fake.itemCount)
        val moved = item.copy(stroke = dot)
        s.record(ReplaceItems(0, listOf(item), listOf(moved)))
        assertEquals(0, fake.itemCount)
        s.undo() // the move again, backwards: still nothing to move
        assertEquals(0, fake.itemCount)
        s.undo() // the erase: the stroke is back where it was erased
        assertEquals(listOf(line), strokesOn(0))
    }

    /** An add of an id that is already on the page (a replay, say) can't double the item. */
    @Test fun addOfExistingIdIsSkipped() {
        val s = session()
        s.addStroke(0, line)
        val item = fake.items(0).single()
        s.record(AddItems(0, listOf(item, InkItem("new1", dot))))
        assertEquals(listOf(line, dot), strokesOn(0))
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

    /** The screen marks the pages to redraw, and goes to an undone change's page (decision 10). */
    @Test fun undoAndRedoSayWhatTheyRecorded() {
        val s = session()
        s.addStroke(3, dot)
        val undone = s.undo()
        assertTrue(undone is RemoveItems)
        assertEquals(setOf(3), undone!!.pages)
        assertEquals(setOf(3), s.redo()!!.pages)
        assertTrue(s.redo() == null) // nothing left to redo
        s.undo()
        assertTrue(s.undo() == null) // nothing left to undo
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

    /** As a full tablet does: from now on the journal can't be appended to or rewritten. */
    private fun breakTheJournal() {
        val journalFile = ChangeJournal.forDocument(journals, pdf).file
        assertTrue(journalFile.delete())
        assertTrue(journalFile.mkdir())
    }

    /** Review 1.3: with the tablet full, a stroke is kept in memory and saved at once, and nothing crashes. */
    @Test fun strokeIsKeptWhenTheJournalCantBeWritten() {
        val s = session()
        breakTheJournal()
        s.addStroke(0, dot)
        s.addStroke(0, line)
        s.addStroke(1, dot)
        assertEquals(1, events.count { it == SessionEvent.JournalFailed }) // told once, not at every stroke
        assertEquals(listOf(dot, line), strokesOn(0))
        assertEquals(listOf(dot), strokesOn(1))
        assertEquals("items=3", pdf.readText()) // saved without waiting for the autosave
    }

    /** The PDF was saved, so the save counts, even though the journal couldn't be brought up to date. */
    @Test fun saveStillCountsWhenTheJournalCantBeRewritten() {
        val s = session()
        breakTheJournal()
        s.addStroke(0, dot)
        assertEquals("items=1", pdf.readText())
        assertTrue(events.contains(SessionEvent.Saved))
        assertTrue(events.none { it is SessionEvent.SaveFailed })
        val saves = fake.saves
        s.saveNow()
        scheduler.runDelayed()
        assertEquals(saves, fake.saves) // nothing left to save
    }

    /** Review 1.4: an opening that fails with something other than an Exception still says so. */
    @Test fun openThatBlowsUpReportsOpenFailed() {
        opening = { throw OutOfMemoryError() }
        val s = session()
        assertEquals(1, events.count { it is SessionEvent.OpenFailed })
        s.addStroke(0, dot) // returns: the wait for the journal was ended
    }

    /** Review 1.4: running out of memory in a save is a failed save like any other, told and tried again. */
    @Test fun saveThatRunsOutOfMemoryIsReportedAndTriedAgain() {
        val s = session()
        fake.failSaveWith = OutOfMemoryError()
        s.addStroke(0, dot)
        s.saveNow()
        assertTrue(events.any { it is SessionEvent.SaveFailed })
        assertEquals("original", pdf.readText())
        fake.failSaveWith = null
        scheduler.runDelayed() // the retry
        assertTrue(events.contains(SessionEvent.Saved))
        assertEquals("items=1", pdf.readText())
    }

    /**
     * Review 2.1: the app is killed just after the new PDF is swapped in, before the journal is
     * brought up to date. The journal says which save was under way, so the next opening knows
     * the first two strokes are in the file and puts back only the third.
     */
    @Test fun killedAfterTheSwapReplaysOnlyTheRest() {
        val a = InkItem("a", dot)
        val b = InkItem("b", line)
        val c = InkItem("c", dot)
        val journal = ChangeJournal.forDocument(journals, pdf)
        journal.start(FileIdentity.of(pdf)) // the file before the save
        listOf(a, b, c).forEach { journal.append(AddItems(0, listOf(it))) }
        pdf.writeText("items=2") // the save that was swapped in: it held the first two
        journal.file.appendText("saving ${FileIdentity.of(pdf).encode()} 2\n")
        fake.pageItems[0] = mutableListOf(a, b)

        session()
        assertTrue(events.contains(SessionEvent.Replayed(1)))
        assertTrue(events.none { it is SessionEvent.EarlierWritingKept })
        assertEquals(listOf<Item>(a, b, c), fake.items(0))
        assertEquals(listOf<Change>(AddItems(0, listOf(c))), journalChanges()) // and the journal now starts from the saved file
        scheduler.runDelayed()
        assertEquals("items=3", pdf.readText())
    }

    /** The same kill, played through a real save: the journal can be added to but not replaced. */
    @Test fun saveMarksTheJournalBeforeTheSwap() {
        val s = session()
        s.addStroke(0, dot)
        s.addStroke(0, line)
        journals.setWritable(false) // the rewrite after the save needs a new file in the folder
        try {
            assumeFalse("can't lock the folder here (running as root?)", journals.canWrite())
            s.saveNow()
            assertEquals("items=2", pdf.readText())
            s.addStroke(0, dot) // after the save, never saved
        } finally {
            journals.setWritable(true)
        }
        val all = fake.items(0)
        events.clear()
        fake = FakePdfPages().apply { pageItems[0] = all.take(2).toMutableList() } // the file as saved
        session()
        assertTrue(events.none { it is SessionEvent.EarlierWritingKept })
        assertTrue(events.contains(SessionEvent.Replayed(1)))
        assertEquals(all, fake.items(0))
    }

    /** Review 2.2: a sync app reset the file's date after a crash. The unsaved writing still comes back. */
    @Test fun journalReplaysWhenOnlyTheDateChanged() {
        ChangeJournal.forDocument(journals, pdf).apply { start(FileIdentity.of(pdf)); append(AddItems(0, listOf(InkItem("a", dot)))) }
        pdf.setLastModified(pdf.lastModified() + 60_000)
        session()
        assertTrue(events.contains(SessionEvent.Replayed(1)))
        assertTrue(events.none { it is SessionEvent.EarlierWritingKept })
        assertEquals(1, fake.itemCount)
    }

    /** Review 2.2: the same while the file is open. Its contents are as they were, so it's saved into. */
    @Test fun aTouchedFileIsStillSavedInto() {
        val s = session()
        pdf.setLastModified(pdf.lastModified() + 60_000)
        s.addStroke(0, dot)
        s.saveNow()
        assertTrue(events.contains(SessionEvent.Saved))
        assertFalse(events.contains(SessionEvent.ChangedOnDisk))
        assertEquals("items=1", pdf.readText())
    }

    /**
     * Review 4.6: a snip's picture is in the journal once, and named after that. A snip is
     * pasted and moved, saved part-way, moved again, undone and redone, and then the app is
     * killed. The next opening must put back exactly what was recorded after the save.
     */
    @Test fun aMovedSnipComesBackAfterAKill() {
        val png = ByteArray(5000) { (it * 13).toByte() }
        fun at(x: Float) = ImageItem("snip", Box(x, 0f, x + 100f, 50f), 0, png)
        val s = session()
        s.record(AddItems(0, listOf(at(0f))))
        s.record(ReplaceItems(0, listOf(at(0f)), listOf(at(10f))))
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("items=2", pdf.readText())
        val saved = fake.items(0)

        // After the save the journal starts afresh: the picture has to be written into it again.
        val recorded = listOf(
            s.record(ReplaceItems(0, listOf(at(10f)), listOf(at(20f))))!!,
            s.record(ReplaceItems(0, listOf(at(20f)), listOf(at(30f))))!!,
            s.undo()!!,
            s.redo()!!,
            s.record(ReplaceItems(0, listOf(at(30f)), listOf(at(40f))))!!,
        )
        val expected = fake.items(0)
        assertEquals(at(40f), expected.single { it is ImageItem })
        val journalFile = ChangeJournal.forDocument(journals, pdf).file
        assertEquals(1, journalFile.readLines().count { it.startsWith("img ") })
        assertTrue("${journalFile.length()} bytes", journalFile.length() < 2 * png.size)

        // Killed. The file is as the save left it.
        events.clear()
        fake = FakePdfPages().apply { pageItems[0] = saved.toMutableList() }
        val next = session()
        assertTrue(events.contains(SessionEvent.Replayed(recorded.size)))
        assertTrue(events.none { it is SessionEvent.EarlierWritingKept })
        assertEquals(expected, fake.items(0))
        assertEquals(recorded, journalChanges())

        // And the new session carries on in the same journal: one more move, another kill.
        next.record(ReplaceItems(0, listOf(at(40f)), listOf(at(50f))))
        assertEquals(1, journalFile.readLines().count { it.startsWith("img ") })
        fake = FakePdfPages().apply { pageItems[0] = saved.toMutableList() }
        session()
        assertEquals(at(50f), fake.items(0).single { it is ImageItem })
    }

    /** The same, killed between the PDF being swapped in and the journal hearing of it. */
    @Test fun aMovedSnipComesBackAfterAKillInTheMiddleOfASave() {
        val png = ByteArray(5000) { (it * 13).toByte() }
        fun at(x: Float) = ImageItem("snip", Box(x, 0f, x + 100f, 50f), 0, png)
        val s = session()
        s.record(AddItems(0, listOf(at(0f))))
        s.record(ReplaceItems(0, listOf(at(0f)), listOf(at(10f))))
        journals.setWritable(false) // the rewrite after the save needs a new file in the folder
        try {
            assumeFalse("can't lock the folder here (running as root?)", journals.canWrite())
            s.saveNow()
            assertEquals("items=1", pdf.readText())
            s.record(ReplaceItems(0, listOf(at(10f)), listOf(at(20f)))) // after the save, never saved
        } finally {
            journals.setWritable(true)
        }
        events.clear()
        fake = FakePdfPages().apply { pageItems[0] = mutableListOf(at(10f)) } // the file as saved
        session()
        assertTrue(events.none { it is SessionEvent.EarlierWritingKept })
        assertTrue(events.contains(SessionEvent.Replayed(1)))
        assertEquals(listOf<Item>(at(20f)), fake.items(0))
        // The journal written afresh at this opening holds the picture for the one move left.
        assertEquals(listOf<Change>(ReplaceItems(0, listOf(at(10f)), listOf(at(20f)))), journalChanges())
    }
}
