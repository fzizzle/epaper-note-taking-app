package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.geometry.Pt
import com.fzizzle.epapernotes.ink.Box
import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Item
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.storage.CopyNames
import com.fzizzle.epapernotes.storage.FileIdentity
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
    private var opening: (File) -> PdfPages = { f -> if (isACopy(f)) FakePdfPages(pages = fake.pageCount) else fake }
    private fun isACopy(f: File) = "(my edits" in f.name
    /** Runs at each event after it's noted, so a test can make one fail. */
    private var onEvent: (SessionEvent) -> Unit = {}
    /** Every file whose identity the session read (each read goes through the whole file). */
    private val identityReads = mutableListOf<File>()
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
        events = { events += it; onEvent(it) },
        clock = { LocalDateTime.of(2026, 11, 9, 14, 5) },
        identityOf = { identityReads += it; FileIdentity.of(it) },
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

    private val myEdits get() = File(pdf.parentFile, "Lecture (my edits 2026-11-09).pdf")

    /** Spec §7.4: changed on disk with unsaved writing. Both are kept, and the session goes on in the copy. */
    @Test fun aFileChangedWhileOpenIsLeftAloneAndTheWritingGoesIntoACopy() {
        val s = session()
        pdf.writeText("edited on the laptop")
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("edited on the laptop", pdf.readText())
        assertEquals("items=1", myEdits.readText())
        assertTrue(events.contains(SessionEvent.SavedToCopy(myEdits, CopyReason.CHANGED_WHILE_OPEN)))
        assertEquals(myEdits, s.file)
        // Everything after it goes into the copy too, undo included.
        s.addStroke(0, line)
        s.undo()
        s.undo()
        s.saveNow()
        assertEquals("items=0", myEdits.readText())
        assertEquals("edited on the laptop", pdf.readText())
        assertEquals(0, journalStrokes(myEdits))
        assertFalse(ChangeJournal.forDocument(journals, pdf).file.exists())
    }

    /** A kill right after the copy is made: the next opening of either file loses and doubles nothing. */
    @Test fun aKillAfterTheConflictCopyLosesNothing() {
        val s = session()
        pdf.writeText("edited on the laptop")
        s.addStroke(0, dot)
        s.saveNow()
        s.addStroke(1, line) // in the copy's journal only
        reopenAfterKill()
        assertTrue(events.none { it is SessionEvent.SavedToCopy || it is SessionEvent.EarlierWritingKept })
        assertEquals(1, journalStrokes(myEdits))
    }

    /** Spec §7.4: changed on disk, nothing unsaved. Once the screen holds the pen, the new version is opened in its place. */
    @Test fun aFileChangedWhileOpenWithNothingUnsavedIsOpenedAgain() {
        val s = session()
        s.addStroke(0, dot)
        s.saveNow() // saved: nothing unsaved now, but one step to undo
        pdf.writeText("edited on the laptop")
        val newVersion = FakePdfPages().apply { pageItems[3] = mutableListOf(InkItem("theirs", line)) }
        fake = newVersion
        s.checkFile()
        assertTrue(events.contains(SessionEvent.ChangedElsewhere))
        s.reload() // what the screen does once the pen is held
        assertTrue(events.contains(SessionEvent.Reloaded))
        assertTrue(events.none { it is SessionEvent.SavedToCopy })
        assertFalse(myEdits.exists())
        assertEquals(SessionEvent.HistoryChanged(canUndo = false, canRedo = false), lastHistory())
        // Writing goes into the new version from here on.
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("items=2", pdf.readText())
        assertEquals(2, newVersion.itemCount)
    }

    /** Old ink drawn again isn't writing: it never makes a conflict copy (it's redrawn on the new version). */
    @Test fun redrawnInkAloneNeverMakesAConflictCopy() {
        fake.faultyStrokes[0] = 2
        val s = session()
        scheduler.runDelayed() // the redraw, a moment after opening; it asks for a save
        pdf.writeText("edited on the laptop")
        val old = fake
        fake = FakePdfPages()
        s.saveNow()
        assertEquals(0, old.saves)
        assertTrue(events.contains(SessionEvent.ChangedElsewhere))
        s.reload()
        assertTrue(events.contains(SessionEvent.Reloaded))
        assertFalse(myEdits.exists())
        assertEquals("edited on the laptop", pdf.readText())
    }

    /** Coming back to the screen looks at the file's size and date first; the whole file is read only if they differ. */
    @Test fun lookingAtAnUnchangedFileDoesNotReadIt() {
        val s = session()
        identityReads.clear()
        s.checkFile()
        assertTrue(identityReads.isEmpty())
        assertTrue(events.none { it is SessionEvent.Reloaded || it is SessionEvent.ChangedElsewhere })
    }

    /** Decision 1: a changed file with nothing unsaved isn't swapped in behind the pen. The screen is asked first. */
    @Test fun aChangedFileWithNothingUnsavedAsksFirst() {
        val s = session()
        pdf.writeText("edited on the laptop")
        s.checkFile()
        assertEquals(1, events.count { it == SessionEvent.ChangedElsewhere })
        assertTrue(events.none { it is SessionEvent.Reloaded })
        assertFalse(fake.closed) // the old version is still the one open
    }

    /** The file changed, the screen held the pen and asked: the new version is opened, under new page numbers. */
    private fun reloadOnto(newVersion: FakePdfPages): DocumentSession {
        val s = session()
        pdf.writeText("edited on the laptop")
        s.checkFile()
        assertTrue(events.contains(SessionEvent.ChangedElsewhere))
        opening = { newVersion }
        s.reload()
        return s
    }

    @Test fun reloadOpensTheNewVersion() {
        val old = fake
        val s = reloadOnto(FakePdfPages(pages = 3))
        assertTrue(events.contains(SessionEvent.Reloaded))
        assertTrue(old.closed)
        assertEquals(1, s.pageNumbering)
        assertTrue(ChangeJournal.forDocument(journals, pdf).read()!!.base.sameContentAs(FileIdentity.of(pdf)))
    }

    /** Review focus 2: a stroke written before the pen was held is on the old version's pages, so it goes into a copy of those. */
    @Test fun aStrokeBeforeTheReloadGoesIntoACopyOfTheOldVersion() {
        val s = session()
        pdf.writeText("edited on the laptop")
        s.checkFile()
        assertEquals(1, events.count { it == SessionEvent.ChangedElsewhere })
        s.addStroke(0, dot) // the pen wasn't held yet
        val newVersion = FakePdfPages()
        opening = { newVersion }
        s.reload()
        assertTrue(events.contains(SessionEvent.NotReloaded))
        assertTrue(events.none { it is SessionEvent.Reloaded })
        assertTrue(events.contains(SessionEvent.SavedToCopy(myEdits, CopyReason.CHANGED_WHILE_OPEN)))
        assertEquals("items=1", myEdits.readText())
        assertEquals("edited on the laptop", pdf.readText())
        assertTrue(newVersion.closed)
    }

    /** A stroke the pen handed over late, read under the old numbers: on a page the new version has, it keeps it. */
    @Test fun aLateStrokeAfterAReloadKeepsItsPage() {
        val newVersion = FakePdfPages(pages = 5)
        val s = reloadOnto(newVersion)
        fake = newVersion
        assertEquals(2, (s.addStroke(2, dot, numbering = 0) as AddItems).page)
        assertEquals(listOf(dot), strokesOn(2))
    }

    /** Review focus 3: the new version is shorter, and a late stroke from a page it lacks has nowhere to go. Saving goes on. */
    @Test fun aLateStrokeOnAPageTheNewVersionLacksIsNotRecorded() {
        val newVersion = FakePdfPages(pages = 3)
        val s = reloadOnto(newVersion)
        fake = newVersion
        assertEquals(null, s.addStroke(4, dot, numbering = 0))
        assertTrue(events.none { it is SessionEvent.WritingStopped })
        s.addStroke(0, line)
        s.saveNow()
        assertEquals("items=1", pdf.readText())
        assertEquals(listOf(line), strokesOn(0))
    }

    /** The new version can't be opened (out of memory, say): the old one stays open and on screen, and the pen comes back. */
    @Test fun aNewVersionThatCanNotBeOpenedLeavesTheOldOneOnScreen() {
        val s = session()
        pdf.writeText("edited on the laptop")
        s.checkFile()
        opening = { throw OutOfMemoryError("opening") }
        s.reload()
        assertTrue(events.contains(SessionEvent.NotReloaded))
        assertTrue(events.none { it is SessionEvent.OpenFailed || it is SessionEvent.Reloaded })
        assertFalse(fake.closed)
        s.addStroke(1, dot)
        assertEquals(listOf(dot), strokesOn(1))
    }

    /**
     * Final review 6: the new version opens, but reading its page count fails (memory running
     * out, say). The journal must stay on the old version, which stays on screen: moved onto the
     * new one, a stroke on the old pages and a kill would replay it onto the new version's pages.
     */
    @Test fun aReloadThatFailsMidwayLeavesTheJournalOnTheOldVersion() {
        val s = session()
        val oldIdentity = FileIdentity.of(pdf)
        pdf.writeText("edited on the laptop")
        s.checkFile()
        val newVersion = FakePdfPages()
        val failing = object : PdfPages by newVersion {
            override val pageCount: Int get() = throw OutOfMemoryError("counting pages")
        }
        opening = { failing }
        s.reload()
        assertEquals(1, events.count { it == SessionEvent.NotReloaded })
        assertTrue(events.none { it is SessionEvent.Reloaded })
        assertTrue(newVersion.closed)
        assertFalse(fake.closed)
        assertEquals(0, s.pageNumbering)
        assertTrue(ChangeJournal.forDocument(journals, pdf).read()!!.base.sameContentAs(oldIdentity))
    }

    /** By the time the screen asks, the file is as it was: nothing to open. */
    @Test fun aReloadOfAFileThatChangedBackAnswersNotReloaded() {
        val s = session()
        val modified = pdf.lastModified()
        pdf.writeText("edited on the laptop")
        s.checkFile()
        assertTrue(events.contains(SessionEvent.ChangedElsewhere))
        pdf.writeText("original")
        pdf.setLastModified(modified)
        val newVersion = FakePdfPages()
        opening = { newVersion }
        s.reload()
        assertTrue(events.contains(SessionEvent.NotReloaded))
        assertTrue(events.none { it is SessionEvent.Reloaded })
        assertFalse(fake.closed)
        assertFalse(newVersion.closed) // never opened
        assertEquals(0, s.pageNumbering)
    }

    /** Review A9: the old version's ink pass stops at a reload, and the new version is walked once, from page 0. */
    @Test fun anInkRedrawUnderWayStopsAtAReload() {
        val calls = mutableListOf<String>()
        lateinit var s: DocumentSession
        fake.faultyStrokes += mapOf(0 to 1, 1 to 1, 2 to 1)
        fake.duringRedraw = { page ->
            calls += "old $page"
            // In the middle of the pass: a sync replaces the file, and the screen asks for it.
            if (page == 1) {
                pdf.writeText("edited on the laptop")
                s.checkFile()
                s.reload()
            }
        }
        val newVersion = FakePdfPages().apply { duringRedraw = { calls += "new $it" } }
        s = session()
        opening = { newVersion }
        scheduler.runDelayed() // the old version's pass, a moment after opening
        assertTrue(events.contains(SessionEvent.Reloaded))
        assertEquals(listOf("old 0", "old 1"), calls)
        scheduler.runDelayed() // the new version's pass
        assertEquals(listOf("old 0", "old 1") + (0 until 5).map { "new $it" }, calls)
    }

    /** The copy can't be saved (the folder went with the file, say): told, tried again, and nothing lost meanwhile. */
    @Test fun aConflictCopyThatCanNotBeSavedIsTriedAgain() {
        val s = session()
        pdf.writeText("edited on the laptop")
        s.addStroke(0, dot)
        fake.failNextSave = true
        s.saveNow()
        assertTrue(events.any { it is SessionEvent.SaveFailed })
        assertFalse(myEdits.exists())
        assertEquals(1, journalStrokes())
        scheduler.runDelayed() // the retry
        assertEquals("items=1", myEdits.readText())
        assertEquals("edited on the laptop", pdf.readText())
    }

    private val sessionClock = LocalDateTime.of(2026, 11, 9, 14, 5)

    /** As [breakTheJournal], for [file]'s journal: a full tablet, so it can't be started. */
    private fun breakTheJournalOf(file: File): File {
        val journalFile = ChangeJournal.forDocument(journals, file).file
        journalFile.delete()
        assertTrue(journalFile.mkdir())
        return journalFile
    }

    /** Every "(my edits …)" copy beside the file. */
    private fun myEditsCopies() = pdf.parentFile!!.listFiles()!!.filter { "(my edits" in it.name }

    /** Review A1: the copy is made, its journal can't be. No copy is left behind, and the retry makes just one. */
    @Test fun aCopyWhoseJournalCantBeStartedLeavesNoCopyBehind() {
        val s = session()
        s.addStroke(0, dot)
        pdf.writeText("edited on the laptop")
        val broken = breakTheJournalOf(CopyNames.myEdits(pdf, sessionClock))
        s.saveNow()
        assertEquals(1, events.count { it is SessionEvent.SaveFailed })
        assertEquals(emptyList<File>(), myEditsCopies())
        assertEquals(pdf, s.file)
        broken.delete() // room again
        scheduler.runDelayed() // the retry
        assertEquals(listOf(myEdits), myEditsCopies())
        assertEquals("items=1", myEdits.readText())
        assertEquals(1, events.count { it == SessionEvent.SavedToCopy(myEdits, CopyReason.CHANGED_WHILE_OPEN) })
        assertEquals("edited on the laptop", pdf.readText())
    }

    /** Review A3 / decision 2: a copy MuPDF had to repair isn't trusted. It goes, the writing stays where it was. */
    @Test fun aCopyThatDoesNotOpenCleanIsNotTrusted() {
        val s = session()
        s.addStroke(0, dot)
        pdf.writeText("edited on the laptop")
        opening = { f -> if (isACopy(f)) FakePdfPages(wasRepaired = true, pages = fake.pageCount) else fake }
        s.saveNow()
        assertEquals(1, events.count { it is SessionEvent.SaveFailed })
        assertEquals(emptyList<File>(), myEditsCopies())
        assertEquals(pdf, s.file)
        assertEquals(1, journalStrokes())
    }

    @Test fun aCopyWithTheWrongPageCountIsNotTrusted() {
        val s = session()
        s.addStroke(0, dot)
        pdf.writeText("edited on the laptop")
        opening = { f -> if (isACopy(f)) FakePdfPages(pages = 2) else fake }
        s.saveNow()
        assertEquals(1, events.count { it is SessionEvent.SaveFailed })
        assertEquals(emptyList<File>(), myEditsCopies())
        assertEquals(pdf, s.file)
        assertEquals(1, journalStrokes())
    }

    /** The fault itself: a PDF opened by path counts the overwriting file's pages, and so does the copy saved from it. */
    @Test fun aCopyMatchingTheOpenDocumentsChangedCountIsNotTrusted() {
        val s = session() // opened with 5 pages
        s.addStroke(0, dot)
        pdf.writeText("overwritten by a shorter file")
        fake.loseAllButPages(2) // the open document now says 2; the copy saved from it has 2
        s.saveNow()
        assertEquals(1, events.count { it is SessionEvent.SaveFailed })
        assertEquals(emptyList<File>(), myEditsCopies())
        assertEquals(pdf, s.file)
        assertEquals(1, journalStrokes())
    }

    /** The session's own page changes move the count it checks against. */
    @Test fun aCopyAfterAnAddedPageIsTrustedWithOneMorePage() {
        val s = session()
        s.addPageAfter(0)
        events.clear()
        s.addStroke(0, dot)
        pdf.writeText("edited on the laptop")
        s.saveNow()
        assertTrue(events.none { it is SessionEvent.SaveFailed })
        assertEquals(listOf(myEdits), myEditsCopies())
    }

    @Test fun aCopyThatOpensCleanIsUsed() {
        val s = session()
        s.addStroke(0, dot)
        pdf.writeText("edited on the laptop")
        val checked = mutableListOf<FakePdfPages>()
        opening = { f -> if (isACopy(f)) FakePdfPages(pages = fake.pageCount).also { checked += it } else fake }
        s.saveNow()
        assertEquals(1, checked.size) // one extra open, on the copy
        assertTrue(checked.single().closed)
        assertFalse(fake.closed)
        assertEquals(listOf(myEdits), myEditsCopies())
        assertEquals(myEdits, s.file)
    }

    /** Review A1: a failed move into the copy leaves the journal as it was, so writing after it survives a kill. */
    @Test fun strokesAfterAFailedSwitchSurviveAKill() {
        val s = session()
        s.addStroke(0, dot)
        pdf.writeText("edited on the laptop")
        val broken = breakTheJournalOf(CopyNames.myEdits(pdf, sessionClock))
        s.saveNow()
        assertEquals(1, events.count { it is SessionEvent.SaveFailed })
        s.addStroke(1, line)
        broken.delete() // room again; then the app is killed
        reopenAfterKill()
        assertTrue(events.contains(SessionEvent.SavedToCopy(myEdits, CopyReason.RESCUED)))
        assertEquals(listOf(myEdits), myEditsCopies())
        assertEquals("items=2", myEdits.readText())
    }

    /** A rescue whose copy can't get a journal goes the way any failed rescue goes: set aside, the file opened plain. */
    @Test fun aRescueWhoseJournalCantBeStartedOpensTheFilePlain() {
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(InkItem("a", dot)))) }
        breakTheJournalOf(CopyNames.myEdits(pdf, sessionClock))
        val s = session()
        assertTrue(events.none { it is SessionEvent.OpenFailed })
        assertEquals(1, events.count { it is SessionEvent.EarlierWritingKept })
        assertEquals(emptyList<File>(), myEditsCopies())
        assertEquals(pdf, s.file)
    }

    /** Review A7: a session that moves into a copy, then into a second one, frees both when it closes. */
    @Test fun aSessionThatMovedTwiceFreesBothCopies() {
        val s = session()
        s.addStroke(0, dot)
        pdf.writeText("edited on the laptop")
        s.saveNow()
        assertEquals(myEdits, s.file)
        myEdits.writeText("edited on the laptop too")
        s.addStroke(0, line)
        s.saveNow()
        assertEquals(2, myEditsCopies().size)
        s.close()
        assertFalse(DocumentSession.anyOpenIn(pdf.parentFile!!))
    }

    /** Review A8: a file that arrives at the damaged file's copy's name meanwhile is never replaced. */
    @Test fun aDamagedFilesCopyNeverReplacesAFileThatArrived() {
        fake = FakePdfPages(wasRepaired = true)
        val s = session()
        s.addStroke(0, dot)
        fake.duringSave = { fake.duringSave = null; myEdits.writeText("from the laptop") }
        s.saveRepairedToCopy()
        assertEquals("from the laptop", myEdits.readText())
        assertTrue(events.any { it is SessionEvent.SaveFailed })
        assertEquals(pdf, s.file)
        s.saveRepairedToCopy() // the owner asks again
        val next = File(pdf.parentFile, "Lecture (my edits 2026-11-09 14-05).pdf")
        assertEquals("items=1", next.readText())
        assertEquals("from the laptop", myEdits.readText())
        assertTrue(events.contains(SessionEvent.SavedToCopy(next, CopyReason.DAMAGED)))
        assertEquals("original", pdf.readText())
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

    private val otherVersion = FileIdentity(1L, 1L, "ef".repeat(32))

    /**
     * Spec §7.4, crash replay onto a changed file: the writing never goes into the file. It's
     * put on a copy of the file as it is now (owner's decision, 2026-11-13), which is then open.
     */
    @Test fun writingForAFileChangedSinceIsRescuedIntoACopy() {
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(InkItem("a", dot)))); append(AddItems(2, listOf(InkItem("b", line)))) }
        val s = session()
        assertEquals("original", pdf.readText())
        assertEquals("items=2", myEdits.readText())
        assertEquals(listOf<SessionEvent>(SessionEvent.SavedToCopy(myEdits, CopyReason.RESCUED)), events.filter { it is SessionEvent.SavedToCopy || it is SessionEvent.EarlierWritingKept })
        assertEquals(myEdits, s.file)
        assertFalse(ChangeJournal.forDocument(journals, pdf).file.exists())
        assertEquals(0, journalStrokes(myEdits))
        // Nothing left to save, and new writing goes into the copy.
        s.saveNow()
        assertEquals(0, fake.saves - 1)
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("items=3", myEdits.readText())
        assertEquals("original", pdf.readText())
    }

    /** Opening the original again after a rescue finds nothing more to rescue: no second copy. */
    @Test fun aRescueHappensOnce() {
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(InkItem("a", dot)))) }
        session().close()
        reopenAfterKill()
        assertTrue(events.none { it is SessionEvent.SavedToCopy })
        assertEquals(listOf(myEdits.name, pdf.name), pdf.parentFile!!.listFiles()!!.filter { it.extension == "pdf" }.map { it.name }.sorted())
    }

    /** Writing on a page the file no longer has can't be placed: it's kept aside, and the owner hears how much. */
    @Test fun rescuedWritingOnAPageThatIsGoneIsKeptAside() {
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(InkItem("a", dot)))); append(AddItems(7, listOf(InkItem("b", line)))) }
        session()
        assertEquals("items=1", myEdits.readText())
        assertTrue(events.contains(SessionEvent.SavedToCopy(myEdits, CopyReason.RESCUED, notPlaced = 1)))
        val kept = events.filterIsInstance<SessionEvent.EarlierWritingKept>().single().journal
        assertEquals(2, ChangeJournal(kept).read()!!.changes.size)
    }

    /** Writing that fits nowhere in the file makes no copy: it is kept aside and the file opens plain. */
    @Test fun earlierWritingThatFitsNowhereMakesNoCopy() {
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(45, listOf(InkItem("a", dot)))) }
        val s = session()
        assertEquals(1, events.count { it is SessionEvent.EarlierWritingKept })
        assertTrue(events.none { it is SessionEvent.SavedToCopy })
        assertFalse(myEdits.exists())
        assertEquals(pdf, s.file)
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("items=1", pdf.readText())
    }

    /** Writing the file already holds needs no copy, and nothing is kept aside. */
    @Test fun earlierWritingAlreadyInTheFileMakesNoCopy() {
        val a = InkItem("a", dot)
        val b = InkItem("b", line)
        fake.pageItems[0] = mutableListOf(a, b)
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(a, b))) }
        val s = session()
        assertTrue(events.none { it is SessionEvent.SavedToCopy || it is SessionEvent.EarlierWritingKept })
        assertFalse(myEdits.exists())
        assertEquals(pdf, s.file)
        assertEquals(0, journalStrokes(pdf))
        assertEquals(FileIdentity.of(pdf), ChangeJournal.forDocument(journals, pdf).read()!!.base)
    }

    /** As above, but some of the writing is on pages the file lost: that part is kept aside, once. */
    @Test fun earlierWritingAlreadyInTheFileButPartlyOnMissingPagesIsKeptAside() {
        val a = InkItem("a", dot)
        fake.pageItems[0] = mutableListOf(a)
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(a))); append(AddItems(7, listOf(InkItem("b", line)))) }
        val s = session()
        assertEquals(1, events.count { it is SessionEvent.EarlierWritingKept })
        assertTrue(events.none { it is SessionEvent.SavedToCopy })
        assertFalse(myEdits.exists())
        assertEquals(pdf, s.file)
        val kept = events.filterIsInstance<SessionEvent.EarlierWritingKept>().single().journal
        assertEquals(2, ChangeJournal(kept).read()!!.changes.size)
    }

    /** Only some of the writing is in the file: the rest still goes into a copy. */
    @Test fun earlierWritingPartlyInTheFileStillGoesIntoACopy() {
        val a = InkItem("a", dot)
        fake.pageItems[0] = mutableListOf(a)
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(a, InkItem("b", line)))) }
        val s = session()
        assertTrue(events.contains(SessionEvent.SavedToCopy(myEdits, CopyReason.RESCUED)))
        assertEquals(myEdits, s.file)
    }

    /**
     * Final review 1: reading the page to see whether the writing is already there fails (an
     * annotation MuPDF can't read, or memory running out). That counts as "not there": the
     * writing goes into a copy, and the file still opens, now and every time after.
     */
    @Test fun aPageThatCanNotBeReadForTheAlreadyThereCheckStillGetsACopy() {
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(InkItem("a", dot)))) }
        var reads = 0
        fake.duringItems = { if (reads++ == 0) throw OutOfMemoryError("reading page 0") }
        val s = session()
        assertTrue(events.none { it is SessionEvent.OpenFailed })
        assertTrue(events.contains(SessionEvent.SavedToCopy(myEdits, CopyReason.RESCUED)))
        assertEquals("items=1", myEdits.readText())
        assertEquals(myEdits, s.file)
        assertEquals("original", pdf.readText())
    }

    /** An erase among the writing is not "already there": it still goes into a copy. */
    @Test fun anEraseAmongEarlierWritingStillGoesIntoACopy() {
        val a = InkItem("a", dot)
        fake.pageItems[0] = mutableListOf(a)
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(a))); append(RemoveItems(0, listOf(a))) }
        val s = session()
        assertTrue(events.contains(SessionEvent.SavedToCopy(myEdits, CopyReason.RESCUED)))
        assertEquals(myEdits, s.file)
    }

    /** A rescue that fails leaves the file plain and open, with the writing set aside whole, as before rescues existed. */
    @Test fun aRescueThatFailsSetsTheWritingAside() {
        ChangeJournal.forDocument(journals, pdf).apply { start(otherVersion); append(AddItems(0, listOf(InkItem("a", dot)))) }
        val half = FakePdfPages().apply { failNextApply = true }
        val plain = FakePdfPages()
        val opens = ArrayDeque(listOf(half, plain))
        opening = { opens.removeFirst() }
        val s = session()
        assertTrue(half.closed)
        assertFalse(myEdits.exists())
        assertTrue(events.none { it is SessionEvent.SavedToCopy })
        val kept = events.filterIsInstance<SessionEvent.EarlierWritingKept>().single().journal
        assertEquals(1, ChangeJournal(kept).read()!!.changes.size)
        assertEquals(pdf, s.file)
        s.addStroke(1, line)
        s.saveNow()
        assertEquals("items=1", pdf.readText())
        assertEquals(listOf(line), plain.items(1).map { (it as InkItem).stroke })
    }

    /** An old journal with nothing in it has nothing to rescue: no copy. */
    @Test fun aJournalWithNoChangesOnAChangedFileJustStartsAfresh() {
        ChangeJournal.forDocument(journals, pdf).start(otherVersion)
        val s = session()
        assertTrue(events.none { it is SessionEvent.SavedToCopy || it is SessionEvent.EarlierWritingKept })
        s.addStroke(0, dot)
        s.saveNow()
        assertEquals("items=1", pdf.readText())
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

    /** The library mustn't clean a folder's temp files while a session there may still save (design-gaps Task 1). */
    @Test fun anyOpenInIsTrueUntilTheSessionHasClosed() {
        val folder = pdf.parentFile!!
        assertFalse(DocumentSession.anyOpenIn(folder))
        val s = session()
        assertTrue(DocumentSession.anyOpenIn(folder))
        assertFalse(DocumentSession.anyOpenIn(journals)) // only the folder the file is in
        // Posted from the worker, the close task waits its turn: until it runs, the session counts.
        scheduler.post {
            s.close()
            assertTrue(DocumentSession.anyOpenIn(folder))
        }
        assertFalse(DocumentSession.anyOpenIn(folder))
    }

    @Test fun aCopySessionCountsForItsFolderToo() {
        fake = FakePdfPages(wasRepaired = true)
        val elsewhere = tmp.newFolder("Week 2")
        pdf = File(elsewhere, "Lecture.pdf").apply { writeText("original") }
        val s = session()
        s.addStroke(0, dot)
        s.saveRepairedToCopy()
        assertTrue(DocumentSession.anyOpenIn(elsewhere))
        // Gone from the original's key too once it closes, so nothing is left behind.
        s.close()
        assertFalse(DocumentSession.anyOpenIn(elsewhere))
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
        // Deleted elsewhere: it isn't brought back under its name, and the writing isn't lost.
        assertFalse(pdf.exists())
        assertEquals("items=1", myEdits.readText())
        assertTrue(events.contains(SessionEvent.SavedToCopy(myEdits, CopyReason.CHANGED_WHILE_OPEN)))
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

        assertFalse("events: $secondEvents", secondEvents.any { it is SessionEvent.SavedToCopy || it is SessionEvent.Reloaded })
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

    /**
     * The autosave waits ten seconds since the efficiency plan: a kill inside that wait must
     * cost nothing, because every stroke is in the journal from the moment it's recorded.
     */
    @Test fun aKillBeforeTheAutosaveLosesNothing() {
        val first = session()
        repeat(3) { first.addStroke(0, dot) }
        // Killed: the delayed autosave never runs. A new session finds the three in the journal.
        scheduler.delayed.clear()
        fake = FakePdfPages()
        events.clear()
        session()
        assertTrue(events.contains(SessionEvent.Replayed(3)))
        assertEquals(3, fake.items(0).size)
    }

    /** A stroke starts the autosave's ten seconds, and the next stroke starts them again, under the same key. */
    @Test fun autosaveWaitsTenSecondsAfterTheLastStroke() {
        assertEquals(10_000L, DocumentSession.AUTOSAVE_DELAY_MS)
        val s = session()
        val before = scheduler.postedDelays.size
        s.addStroke(0, dot)
        s.addStroke(0, line)
        val autosaves = scheduler.postedDelays.drop(before).filter { it.first == "autosave" } // DocumentSession.AUTOSAVE
        assertEquals(listOf("autosave" to 10_000L, "autosave" to 10_000L), autosaves)
        assertTrue("one timer waiting, not two", scheduler.delayed.containsKey("autosave"))
    }

    /** A tap with the lasso (a loop a couple of points across) selects the stroke under it, not what's "inside" it. */
    @Test fun aTapWithTheLassoSelectsTheStrokeUnderIt() {
        val s = session()
        s.addStroke(0, line)
        s.addStroke(0, dot)
        var picked: List<Item>? = null
        s.select(0, listOf(Pt(30f, 100f), Pt(31f, 100.5f), Pt(30.5f, 99f)), tapReachPt = 3f) { picked = it }
        assertEquals(listOf(line), picked!!.map { (it as InkItem).stroke })
        s.select(0, listOf(Pt(300f, 300f), Pt(301f, 300f)), tapReachPt = 3f) { picked = it }
        assertEquals(emptyList<Item>(), picked)
        // The same small scribble as a loop (no reach given) holds nothing inside it.
        s.select(0, listOf(Pt(30f, 100f), Pt(31f, 100.5f), Pt(30.5f, 99f))) { picked = it }
        assertEquals(emptyList<Item>(), picked)
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
        assertTrue(events.none { it is SessionEvent.SavedToCopy || it is SessionEvent.Reloaded })
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

    // Pages added and removed (pages plan, Task 4). The fake opens with five pages, 0 to 4.

    private fun pageNotChanged() = events.filterIsInstance<SessionEvent.PageNotChanged>()

    /** As the file on disk is after a failed save: the fake opened again from it, without the added page. */
    private fun reopenFreshFromTheFile() {
        opening = { FakePdfPages().also { fake = it } }
    }

    @Test fun addingAPageSavesAndRenumbers() {
        val s = session()
        s.addStroke(0, dot)
        s.addPageAfter(0)
        assertEquals(6, fake.pageCount) // one more page than before
        assertTrue(fake.isAddedPage(1))
        assertEquals(listOf(dot), strokesOn(0))
        assertEquals("items=1", pdf.readText())
        assertEquals(SessionEvent.Saved, events[events.size - 3])
        assertEquals(SessionEvent.PagesRenumbered(1), events.last())
        assertEquals(0, journalStrokes()) // nothing unsaved
    }

    /** A page is saved at once, even with no writing waiting to be saved. */
    @Test fun anAddedPageIsSavedWithNothingElseUnsaved() {
        val s = session()
        s.addPageAfter(2)
        assertEquals(1, fake.saves)
        assertEquals(SessionEvent.PagesRenumbered(3), events.last())
    }

    /**
     * Recorded before the add's turn on the worker, with the page numbers as they were: it goes
     * in under those numbers first, then moves with its page.
     */
    @Test fun aStrokeRecordedJustBeforeStaysOnItsPage() {
        val s = session()
        scheduler.post {
            s.addPageAfter(0)
            s.addStroke(1, dot) // recorded after the request, before the worker gets to it: not yet applied
        }
        assertEquals(listOf(dot), strokesOn(2)) // the old page 1
        assertTrue(strokesOn(1).isEmpty()) // the new page is empty
        assertEquals("items=1", pdf.readText())
        assertEquals(0, journalStrokes())
    }

    @Test fun addingAPageEmptiesTheUndoList() {
        val s = session()
        s.addStroke(0, dot)
        s.addPageAfter(0)
        assertEquals(SessionEvent.HistoryChanged(canUndo = false, canRedo = false), events[events.size - 2])
        assertEquals(null, s.undo())
        assertEquals(null, s.redo())
    }

    @Test fun noPageIsAddedToAFileChangedElsewhere() {
        val s = session()
        s.addStroke(0, dot)
        pdf.writeText("edited on the laptop")
        s.addPageAfter(0)
        assertEquals(1, pageNotChanged().size)
        assertEquals(5, fake.pageCount)
        assertTrue(fake.addedPages.isEmpty()) // never asked to add
        assertEquals("edited on the laptop", pdf.readText())
        // The stroke is safe in a copy, which has no new page either; adding one there is the owner's next tap.
        assertEquals("items=1", myEdits.readText())
        assertEquals(0, journalStrokes(myEdits))
        s.addPageAfter(0)
        assertEquals(6, fake.pageCount)
        assertEquals("edited on the laptop", pdf.readText())
    }

    @Test fun noPageIsAddedWhileTheRepairQuestionIsOpen() {
        fake = FakePdfPages(wasRepaired = true)
        val s = session()
        s.addPageAfter(0)
        assertEquals(listOf(SessionEvent.PageNotChanged("Answer the question about the damaged file first.")), pageNotChanged())
        assertEquals(5, fake.pageCount)
        assertEquals(0, fake.saves)
        assertEquals("original", pdf.readText())
    }

    @Test fun noPageIsAddedAfterAPageThatIsNotThere() {
        val s = session()
        s.addPageAfter(5)
        s.removeAddedPage(-1)
        assertEquals(2, pageNotChanged().size)
        assertEquals(5, fake.pageCount)
    }

    /** A failed save leaves the open PDF as the file is: without the page, with every stroke on its own page. */
    @Test fun aPageThatCouldNotBeSavedIsTakenBackOut() {
        val s = session()
        s.addStroke(1, dot)
        val added = fake
        added.failNextSave = true
        reopenFreshFromTheFile()
        s.addPageAfter(0)
        assertTrue(events.any { it is SessionEvent.SaveFailed })
        assertEquals(1, pageNotChanged().size)
        assertTrue(events.none { it is SessionEvent.PagesRenumbered })
        assertTrue(added.closed)
        assertEquals(5, fake.pageCount) // the PDF opened again from the file
        assertEquals(listOf(dot), strokesOn(1))
        assertEquals("original", pdf.readText())
        assertEquals(listOf(1), journalChanges().map { (it as AddItems).page })
        s.saveNow() // and saving carries on as before
        assertEquals("items=1", pdf.readText())
    }

    /** If the file can't even be opened again, the owner is told, and the writing waits in the journal. */
    @Test fun aFileThatCanNotBeOpenedAgainIsNotSilent() {
        val s = session()
        s.addStroke(1, dot)
        fake.failNextSave = true
        opening = { throw IllegalStateException("mupdf error") }
        s.addPageAfter(0)
        assertTrue(events.any { it is SessionEvent.OpenFailed })
        assertEquals(1, pageNotChanged().size)
        assertEquals(1, journalStrokes())
    }

    @Test fun aKillWhileAddingLosesNothing() {
        val s = session()
        s.addStroke(1, dot)
        fake.failNextSave = true // the add's save never reaches the disk, as if killed midway
        reopenFreshFromTheFile()
        s.addPageAfter(0)
        assertEquals("original", pdf.readText())
        opening = { fake }
        reopenAfterKill()
        assertTrue(events.contains(SessionEvent.Replayed(1)))
        assertEquals(5, fake.pageCount)
        assertEquals(listOf(dot), strokesOn(1)) // its old page, in the old file
    }

    /**
     * The renumbering note goes in just before the swap. If the swap then fails, the file is
     * still the old one, so the note must not stay: it would make the next opening keep the
     * stroke aside instead of replaying it.
     */
    @Test fun aPageChangeThatCouldNotBeSavedLeavesNoRenumberingNote() {
        val s = session()
        s.addStroke(1, dot)
        reopenFreshFromTheFile()
        // The swap can't happen: the file's name is taken by a folder that isn't empty. This
        // is after the note is written (and after the file was found unchanged).
        fake.duringSave = {
            pdf.delete()
            pdf.mkdir()
            File(pdf, "x").writeText("x")
        }
        s.addPageAfter(0)
        assertEquals(1, pageNotChanged().size)
        val contents = ChangeJournal.forDocument(journals, pdf).read()!!
        assertFalse(contents.renumbering)
        assertEquals(listOf(dot), contents.changes.map { ((it as AddItems).items[0] as InkItem).stroke })
    }

    /**
     * Killed after the new PDF (with the page) is in place, before the journal hears of it. Its
     * changes have the old page numbers, so they're never replayed onto the new file: they're
     * kept aside, as for any file changed since.
     */
    @Test fun aKillJustAfterTheAddsSwapReplaysNothingOntoTheNewNumbers() {
        val s = session()
        s.addStroke(1, dot)
        journals.setWritable(false) // the rewrite after the save needs a new file in the folder
        try {
            assumeFalse("can't lock the folder here (running as root?)", journals.canWrite())
            s.addPageAfter(0)
            assertEquals("items=1", pdf.readText())
        } finally {
            journals.setWritable(true)
        }
        events.clear()
        fake = FakePdfPages().apply { pageItems[2] = mutableListOf(InkItem("saved", dot)) } // the file as saved
        session()
        assertTrue(events.none { it is SessionEvent.Replayed })
        assertTrue(events.any { it is SessionEvent.EarlierWritingKept })
        assertTrue(strokesOn(1).isEmpty())
        assertEquals(listOf(dot), strokesOn(2))
    }

    /**
     * The pen shouldn't write while a page is added (the screen pauses it), but if a stroke
     * does arrive during the add's save, it has the old page numbers: it moves with its page.
     */
    @Test fun aStrokeArrivingDuringTheAddLandsOnItsOwnPage() {
        val s = session()
        fake.duringSave = { fake.duringSave = null; s.addStroke(2, dot) }
        s.addPageAfter(0)
        assertEquals(listOf(dot), strokesOn(3)) // the old page 2
        assertTrue(strokesOn(2).isEmpty())
        assertEquals(listOf(3), journalChanges().map { (it as AddItems).page })
        reopenAfterKill() // and after a kill it comes back there too
        assertEquals(listOf(dot), strokesOn(3))
    }

    @Test fun removingAnAddedPageWithInkRemovesItsInkToo() {
        val s = session()
        s.addPageAfter(0)
        s.addStroke(1, dot)
        s.addStroke(2, line) // the PDF's own page after it
        s.removeAddedPage(1)
        assertEquals(5, fake.pageCount)
        assertTrue(fake.addedPages.isEmpty())
        assertEquals(listOf(line), strokesOn(1))
        assertEquals("items=1", pdf.readText())
        assertEquals(0, journalStrokes())
        assertEquals(SessionEvent.PagesRenumbered(1), events.last()) // the page now where it was
        assertEquals(null, s.undo())
    }

    @Test fun removingTheLastPageShowsTheOneBefore() {
        val s = session()
        s.addPageAfter(4)
        s.removeAddedPage(5)
        assertEquals(SessionEvent.PagesRenumbered(4), events.last())
    }

    /** A page added in an earlier session can be removed too. */
    @Test fun aPageAddedEarlierCanBeRemoved() {
        fake.addedPages += 3
        val s = session()
        s.removeAddedPage(3)
        assertEquals(4, fake.pageCount)
        assertEquals(SessionEvent.PagesRenumbered(3), events.last())
    }

    @Test fun aPdfsOwnPageIsNotRemoved() {
        val s = session()
        s.addStroke(1, dot)
        s.removeAddedPage(1)
        assertEquals(listOf(SessionEvent.PageNotChanged("Only pages you added can be removed.")), pageNotChanged())
        assertEquals(5, fake.pageCount)
        assertEquals(listOf(dot), strokesOn(1))
        assertEquals(0, fake.saves)
    }

    /**
     * A stroke arriving during the removal, on the very page removed, has nowhere to go in the
     * new file. It isn't dropped: the journal it is in is kept aside, as for a changed file.
     */
    @Test fun aStrokeOnTheRemovedPageArrivingDuringTheRemovalIsKeptAside() {
        fake.addedPages += 1
        val s = session()
        fake.duringSave = { fake.duringSave = null; s.addStroke(1, dot); s.addStroke(3, line) }
        s.removeAddedPage(1)
        val kept = events.filterIsInstance<SessionEvent.EarlierWritingKept>().single().journal
        assertEquals(listOf(1, 3), journalOf(kept).map { (it as AddItems).page }) // as recorded, old numbers
        assertEquals(listOf(line), strokesOn(2)) // the other moved with its page
        assertEquals(listOf(2), journalChanges().map { (it as AddItems).page })
        assertEquals(4, fake.pageCount)
    }

    private fun journalOf(file: File) = ChangeJournal(file).read()!!.changes

    /**
     * Review r1: the old journal is kept aside, then the new one can't be written (a nearly
     * full tablet). The old one must still be where the next strokes go, with its header, so a
     * kill afterwards keeps them too, instead of a headerless file that the next opening drops.
     */
    @Test fun strokesAfterAKeptJournalAndAFailedRewriteSurviveAKill() {
        fake.addedPages += 1
        val s = session()
        fake.duringSave = { fake.duringSave = null; s.addStroke(1, dot) }
        val blocker = File(journals, ChangeJournal.forDocument(journals, pdf).file.name + ".new").apply { mkdirs() } // the rewrite can't write its new file
        s.removeAddedPage(1)
        assertEquals(1, events.count { it is SessionEvent.EarlierWritingKept })
        s.addStroke(2, line) // after the removal, before any other save
        blocker.delete() // room again; then the app is killed
        reopenAfterKill()
        val keptStrokes = journals.listFiles()!!.filter { ".kept-" in it.name }.flatMap { journalOf(it) }
        assertTrue(keptStrokes.any { it is AddItems && (it.items.single() as InkItem).stroke == line })
    }

    @Test fun isAddedPageAnswersOnTheWorker() {
        val s = session()
        s.addPageAfter(1)
        val answers = mutableListOf<Boolean>()
        s.isAddedPage(2) { answers += it }
        s.isAddedPage(1) { answers += it }
        s.isAddedPage(9) { answers += it }
        assertEquals(listOf(true, false, false), answers)
    }

    // Requests made before a page change, arriving after it (Task 5, review r1). The screen
    // latches the session's page numbering when the pen lands and passes it with the stroke.

    /** A late stroke from before an add lands on its own page: one further if after the new page, unchanged before it. */
    @Test fun aLateStrokeFromBeforeAnAddLandsOnItsOwnPage() {
        val s = session()
        val before = s.pageNumbering
        s.addPageAfter(1)
        assertEquals(before + 1, s.pageNumbering)
        s.addStroke(2, line, before) // the old page 2, now 3
        s.addStroke(0, dot, before) // before the new page: unchanged
        assertEquals(listOf(line), strokesOn(3))
        assertTrue(strokesOn(2).isEmpty()) // the added page stays empty
        assertEquals(listOf(dot), strokesOn(0))
        assertEquals(listOf(3, 0), journalChanges().map { (it as AddItems).page })
    }

    /** Across a removal later pages move one earlier, and a stroke on the page removed has nowhere to go: not recorded. */
    @Test fun aLateStrokeFromBeforeARemovalMovesOrIsNotRecorded() {
        fake.addedPages += 1
        val s = session()
        val before = s.pageNumbering
        s.removeAddedPage(1)
        s.addStroke(3, line, before) // the old page 3, now 2
        s.addStroke(1, dot, before) // the page removed
        assertEquals(listOf(line), strokesOn(2))
        assertEquals(listOf(2), journalChanges().map { (it as AddItems).page })
        assertEquals(1, fake.itemCount)
    }

    @Test fun aLateStrokeIsMovedThroughEveryPageChangeSince() {
        val s = session()
        val before = s.pageNumbering
        s.addPageAfter(0) // old 4 is now 5
        s.addPageAfter(3) // and now 6
        s.addStroke(4, line, before)
        s.addStroke(0, dot, before)
        assertEquals(listOf(line), strokesOn(6))
        assertEquals(listOf(dot), strokesOn(0))
        assertEquals(before + 2, s.pageNumbering)
    }

    /** An erase drawn before a page change isn't worth redoing on a renumbered page: it removes nothing. */
    @Test fun aLateEraseFromBeforeAPageChangeRemovesNothing() {
        val s = session()
        s.addStroke(1, line) // the old page 1 becomes 2, under the trail's page number
        val before = s.pageNumbering
        s.addPageAfter(0)
        var hits = -1
        s.erase(2, acrossLine, before) { hits = it }
        assertEquals(0, hits)
        assertEquals(listOf(line), strokesOn(2))
    }

    /** Stale is decided on the worker, when the erase runs: asked for with the numbering current then, it ran after the change. */
    @Test fun anEraseQueuedBehindAPageChangeRemovesNothing() {
        val s = session()
        s.addStroke(1, line) // moves to page 2 with the add
        var hits = -1
        scheduler.post {
            s.addPageAfter(0)
            // Page 2 as numbered when asked (empty then); the add runs first, and would put the line under it.
            s.erase(2, acrossLine, s.pageNumbering) { hits = it }
        }
        assertEquals(0, hits)
        assertEquals(listOf(line), strokesOn(2))
    }

    @Test fun aLateLassoFromBeforeAPageChangeFindsNothing() {
        val s = session()
        s.addStroke(1, line)
        val before = s.pageNumbering
        s.addPageAfter(0)
        var picked: List<Item>? = null
        s.select(2, listOf(Pt(0f, 80f), Pt(60f, 80f), Pt(60f, 120f), Pt(0f, 120f)), numbering = before) { picked = it }
        assertEquals(emptyList<Item>(), picked)
    }

    /** With the numbering current, strokes, erases and lassos behave as ever. */
    @Test fun theCurrentNumberingChangesNothing() {
        val s = session()
        s.addPageAfter(0)
        val now = s.pageNumbering
        s.addStroke(2, line, now)
        assertEquals(listOf(line), strokesOn(2))
        var picked: List<Item>? = null
        s.select(2, listOf(Pt(0f, 80f), Pt(60f, 80f), Pt(60f, 120f), Pt(0f, 120f)), numbering = now) { picked = it }
        assertEquals(1, picked?.size)
        var hits = -1
        s.erase(2, acrossLine, now) { hits = it }
        assertEquals(1, hits)
        assertTrue(strokesOn(2).isEmpty())
    }

    // Final review of the pages plan.

    /** Running out of memory while adding (an Error, not an Exception) still answers, so the screen lets go of the pen. */
    @Test fun anErrorWhileAddingAPageStillAnswers() {
        val s = session()
        s.addStroke(1, dot)
        fake.failPageChangeWith = OutOfMemoryError("adding")
        reopenFreshFromTheFile()
        s.addPageAfter(0)
        assertEquals(1, pageNotChanged().size)
        assertEquals(5, fake.pageCount) // the PDF opened again from the file
        assertEquals(listOf(dot), strokesOn(1))
        assertEquals(1, journalStrokes())
    }

    @Test fun anErrorWhileOpeningAgainStillAnswers() {
        val s = session()
        s.addStroke(1, dot)
        fake.failNextSave = true
        opening = { throw OutOfMemoryError("opening") }
        s.addPageAfter(0)
        assertTrue(events.any { it is SessionEvent.OpenFailed })
        assertEquals(1, pageNotChanged().size)
        assertEquals(1, journalStrokes())
    }

    /** The screen's Remove waits on this answer: it comes even after an Error, and the Error is still reported. */
    @Test fun anErrorReadingItemsStillAnswersAndIsReported() {
        val s = session()
        fake.duringItems = { throw OutOfMemoryError("items") }
        var answered = false
        assertThrows(OutOfMemoryError::class.java) { s.items(0) { answered = true } }
        assertTrue(answered)
    }

    /**
     * The journal can't be rewritten after a removal for a reason that isn't an IOException.
     * The file has the new numbers by then, and so must what's left unsaved: a stroke from
     * during the removal lands on its own page, not on the old number.
     */
    @Test fun aFailedRewriteAfterARemovalStillLeavesTheNewNumbers() {
        fake.addedPages += 1
        val s = session()
        fake.duringSave = { fake.duringSave = null; s.addStroke(1, dot); s.addStroke(3, line) }
        onEvent = { if (it is SessionEvent.EarlierWritingKept) throw IllegalStateException("can't keep it") }
        s.removeAddedPage(1)
        onEvent = {}
        assertEquals(4, fake.pageCount)
        assertEquals(listOf(line), strokesOn(2)) // the old page 3
        assertEquals(1, fake.itemCount) // the stroke on the removed page went nowhere
        assertTrue(events.contains(SessionEvent.PagesRenumbered(1)))
        s.saveNow()
        assertEquals(listOf(line), strokesOn(2))
    }

    /** The page change checks the file just before changing it, and its save trusts that check: the file is read once, not twice. */
    @Test fun aPageChangeReadsTheFileOnce() {
        val s = session()
        s.addStroke(1, dot)
        identityReads.clear()
        s.addPageAfter(0)
        assertEquals(1, identityReads.count { it == pdf })
        assertEquals(SessionEvent.PagesRenumbered(1), events.last())
        // An ordinary save still checks for itself.
        s.addStroke(0, dot)
        identityReads.clear()
        s.saveNow()
        assertEquals(1, identityReads.count { it == pdf })
    }

    /** A change recorded during the page change's save, applied to the PDF after it, under the new numbers. */
    private fun recordDuringTheChange(s: DocumentSession, change: Change) {
        fake.duringSave = { fake.duringSave = null; s.record(change) }
    }

    private val onPage2 = InkItem("a1", line)

    @Test fun anEraseArrivingDuringAnAddMovesWithItsPage() {
        val s = session()
        s.record(AddItems(2, listOf(onPage2)))
        recordDuringTheChange(s, RemoveItems(2, listOf(onPage2)))
        s.addPageAfter(0)
        assertEquals(listOf<Change>(RemoveItems(3, listOf(onPage2))), journalChanges())
        assertTrue(strokesOn(3).isEmpty())
    }

    @Test fun anEraseArrivingDuringARemovalMovesWithItsPage() {
        fake.addedPages += 1
        val s = session()
        s.record(AddItems(3, listOf(onPage2)))
        recordDuringTheChange(s, RemoveItems(3, listOf(onPage2)))
        s.removeAddedPage(1)
        assertEquals(listOf<Change>(RemoveItems(2, listOf(onPage2))), journalChanges())
        assertTrue(strokesOn(2).isEmpty())
    }

    @Test fun aMoveArrivingDuringAnAddMovesWithItsPage() {
        val s = session()
        s.record(AddItems(2, listOf(onPage2)))
        val moved = InkItem("a1", dot)
        recordDuringTheChange(s, ReplaceItems(2, listOf(onPage2), listOf(moved)))
        s.addPageAfter(0)
        assertEquals(listOf<Change>(ReplaceItems(3, listOf(onPage2), listOf(moved))), journalChanges())
        assertEquals(listOf(dot), strokesOn(3))
    }

    @Test fun aMoveArrivingDuringARemovalMovesWithItsPage() {
        fake.addedPages += 1
        val s = session()
        s.record(AddItems(3, listOf(onPage2)))
        val moved = InkItem("a1", dot)
        recordDuringTheChange(s, ReplaceItems(3, listOf(onPage2), listOf(moved)))
        s.removeAddedPage(1)
        assertEquals(listOf<Change>(ReplaceItems(2, listOf(onPage2), listOf(moved))), journalChanges())
        assertEquals(listOf(dot), strokesOn(2))
    }

    /** Both of a rotation's maps move: the one it applies, and the one its undo would. */
    @Test fun aRotationArrivingDuringAnAddMovesWithItsPages() {
        val s = session()
        recordDuringTheChange(s, RotatePages(mapOf(0 to 0, 2 to 0), mapOf(0 to 90, 2 to 90)))
        s.addPageAfter(0)
        assertEquals(listOf<Change>(RotatePages(mapOf(0 to 0, 3 to 0), mapOf(0 to 90, 3 to 90))), journalChanges())
        assertEquals(mapOf(0 to 90, 3 to 90), fake.rotations)
    }

    @Test fun aRotationArrivingDuringARemovalMovesWithItsPages() {
        fake.addedPages += 1
        val s = session()
        recordDuringTheChange(s, RotatePages(mapOf(0 to 0, 3 to 180), mapOf(0 to 90, 3 to 270)))
        s.removeAddedPage(1)
        assertEquals(listOf<Change>(RotatePages(mapOf(0 to 0, 2 to 180), mapOf(0 to 90, 2 to 270))), journalChanges())
        assertEquals(mapOf(0 to 90, 2 to 270), fake.rotations)
    }
}
