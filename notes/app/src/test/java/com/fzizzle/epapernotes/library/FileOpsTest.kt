package com.fzizzle.epapernotes.library

import com.fzizzle.epapernotes.doc.AddItems
import com.fzizzle.epapernotes.doc.ChangeJournal
import com.fzizzle.epapernotes.doc.DocumentSession
import com.fzizzle.epapernotes.doc.FakePdfPages
import com.fzizzle.epapernotes.doc.FakeScheduler
import com.fzizzle.epapernotes.doc.SessionEvent
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.storage.FileIdentity
import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileOpsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var journals: File
    private val pages = MapMemory()
    private lateinit var ops: FileOps
    private val dot = Stroke(StrokeKind.PEN, listOf(StrokePoint(1f, 2f, 0.5f)))

    /** Last pages in a map, as LastPageStore keeps them in preferences. */
    class MapMemory : PageMemory {
        val map = mutableMapOf<String, Int>()
        override fun move(from: String, to: String) {
            val page = map.remove(from)
            if (page != null) map[to] = page else map.remove(to)
        }
        override fun remove(path: String) {
            map.remove(path)
        }
    }

    @Before fun setUp() {
        root = tmp.newFolder("Documents")
        journals = tmp.newFolder("journals")
        ops = FileOps(root, journals, pages)
    }

    private fun pdf(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText("pdf $path") }

    /** A journal holding unsaved writing, and a remembered page, as an open document leaves them. */
    private fun withAppData(pdf: File, page: Int = 3): File {
        val journal = ChangeJournal.forDocument(journals, pdf)
        journal.start(FileIdentity.of(pdf))
        journal.append(AddItems(0, listOf(InkItem("i1", dot))))
        pages.map[pdf.path] = page
        return journal.file
    }

    private fun journalOf(pdf: File) = ChangeJournal.journalFileFor(journals, pdf)

    @Test fun renameKeepsPdfExtension() {
        val a = pdf("Lecture.pdf")
        assertEquals(FileOps.Result.Done, ops.rename(a, "Week 2"))
        assertTrue(File(root, "Week 2.pdf").exists())
        assertFalse(a.exists())
        // Typing the extension doesn't double it.
        assertEquals(FileOps.Result.Done, ops.rename(File(root, "Week 2.pdf"), "Week 3.pdf"))
        assertTrue(File(root, "Week 3.pdf").exists())
        // A folder gets no extension.
        val folder = File(root, "Physics").apply { mkdir() }
        assertEquals(FileOps.Result.Done, ops.rename(folder, "Maths"))
        assertTrue(File(root, "Maths").isDirectory)
    }

    @Test fun badNamesAreRefused() {
        val a = pdf("Lecture.pdf")
        pdf("Other.pdf")
        File(root, "Physics").mkdir()
        for (bad in listOf("", "   ", "a/b", ".hidden", "other", "OTHER.PDF")) {
            assertTrue("rename to '$bad'", ops.rename(a, bad) is FileOps.Result.Refused)
        }
        for (bad in listOf("", "x/y", ".git", "physics")) {
            assertTrue("new folder '$bad'", ops.newFolder(root, bad) is FileOps.Result.Refused)
        }
        assertTrue(a.exists())
        assertEquals(FileOps.Result.Done, ops.newFolder(root, "Chemistry"))
        assertTrue(File(root, "Chemistry").isDirectory)
    }

    @Test fun renameMovesJournalAndLastPage() {
        val a = pdf("Physics/Lecture.pdf")
        val oldJournal = withAppData(a, page = 7)
        val kept = File(journals, oldJournal.name + ".kept-123").apply { writeText("earlier writing") }

        assertEquals(FileOps.Result.Done, ops.rename(a, "Week 1"))

        val renamed = File(root, "Physics/Week 1.pdf")
        assertFalse(oldJournal.exists())
        assertFalse(kept.exists())
        assertEquals(1, ChangeJournal.forDocument(journals, renamed).read()!!.changes.size)
        assertEquals("earlier writing", File(journals, journalOf(renamed).name + ".kept-123").readText())
        assertEquals(mapOf(renamed.path to 7), pages.map)
    }

    /**
     * Review: a file never opened, renamed to the name of one deleted outside the app, mustn't
     * open at the old file's page. (MapMemory follows LastPageStore's rule; LastPageStore
     * itself needs Android preferences, so it's checked on the tablet, LastPageStoreTest.)
     */
    @Test fun renameOfAFileWithNoPageDropsAStalePageForTheNewName() {
        val a = pdf("Lecture.pdf")
        pages.map[File(root, "Week 1.pdf").path] = 9 // left by an earlier "Week 1.pdf"
        assertEquals(FileOps.Result.Done, ops.rename(a, "Week 1"))
        assertTrue(pages.map.isEmpty())
    }

    @Test fun moveFolderMovesEveryJournalInside() {
        val one = pdf("Physics/One.pdf")
        val two = pdf("Physics/Week 2/TWO.PDF")
        withAppData(one, page = 1)
        withAppData(two, page = 2)
        val year2 = File(root, "Year 2").apply { mkdir() }

        assertEquals(FileOps.Result.Done, ops.move(File(root, "Physics"), year2))

        val newOne = File(root, "Year 2/Physics/One.pdf")
        val newTwo = File(root, "Year 2/Physics/Week 2/TWO.PDF")
        assertTrue(newOne.exists() && newTwo.exists())
        assertFalse(journalOf(one).exists() || journalOf(two).exists())
        assertTrue(journalOf(newOne).exists() && journalOf(newTwo).exists())
        assertEquals(mapOf(newOne.path to 1, newTwo.path to 2), pages.map)
    }

    /** Review 2.11: a folder that can't be read can't be checked for a name clash, so nothing goes in. */
    @Test fun anUnreadableDestinationRefusesTheMove() {
        val one = pdf("One.pdf")
        val locked = File(root, "Locked").apply { mkdir() }
        assertTrue(locked.setReadable(false))
        try {
            org.junit.Assume.assumeTrue("running as a user who can still read it", locked.listFiles() == null)
            assertTrue(ops.move(one, locked) is FileOps.Result.Refused)
            assertTrue(one.exists())
        } finally {
            locked.setReadable(true)
        }
    }

    @Test fun folderCannotMoveIntoItself() {
        val physics = File(root, "Physics").apply { mkdir() }
        val inside = File(physics, "Week 1").apply { mkdir() }
        assertTrue(ops.move(physics, physics) is FileOps.Result.Refused)
        assertTrue(ops.move(physics, inside) is FileOps.Result.Refused)
        // A sibling whose name merely starts the same is fine.
        val physicsLab = File(root, "Physics Lab").apply { mkdir() }
        assertEquals(FileOps.Result.Done, ops.move(physics, physicsLab))
        assertTrue(File(physicsLab, "Physics/Week 1").isDirectory)
    }

    @Test fun nothingOutsideRoot() {
        val outside = tmp.newFile("elsewhere.pdf")
        val elsewhere = tmp.newFolder("Elsewhere")
        val a = pdf("Lecture.pdf")
        assertTrue(ops.rename(outside, "x") is FileOps.Result.Refused)
        assertTrue(ops.delete(outside) is FileOps.Result.Refused)
        assertTrue(ops.move(a, elsewhere) is FileOps.Result.Refused)
        assertTrue(ops.move(outside, root) is FileOps.Result.Refused)
        assertTrue(ops.newFolder(elsewhere, "x") is FileOps.Result.Refused)
        // Nor the library folder itself.
        assertTrue(ops.delete(root) is FileOps.Result.Refused)
        assertTrue(ops.rename(root, "Other") is FileOps.Result.Refused)
        assertTrue(outside.exists() && a.exists() && root.exists())
        // A move that would take a name already there.
        val physics = File(root, "Physics").apply { mkdir() }
        pdf("Physics/lecture.PDF")
        assertTrue(ops.move(a, physics) is FileOps.Result.Refused)
    }

    @Test fun deleteRemovesJournalAndLastPage() {
        val a = pdf("Lecture.pdf")
        val journal = withAppData(a)
        val kept = File(journals, journal.name + ".kept-5").apply { writeText("x") }
        val other = pdf("Physics/One.pdf")
        val two = pdf("Physics/Deeper/Two.pdf")
        withAppData(other); withAppData(two)
        assertEquals(2, ops.pdfCountInside(File(root, "Physics")))

        assertEquals(FileOps.Result.Done, ops.delete(a))
        assertFalse(a.exists() || journal.exists())
        // Earlier writing set aside stays for plan 5 to rescue: the delete question doesn't mention it.
        assertTrue(kept.exists())
        assertEquals(FileOps.Result.Done, ops.delete(File(root, "Physics")))
        assertFalse(File(root, "Physics").exists())
        assertFalse(journalOf(other).exists() || journalOf(two).exists())
        assertTrue(pages.map.isEmpty())
        assertEquals(listOf(kept), journals.listFiles()!!.toList())
    }

    /**
     * The disk refuses the rename (a read-only card, say). Done with a stand-in for the disk move:
     * a read-only folder doesn't stop root, and tests may run as root.
     */
    @Test fun failedRenameMovesNothing() {
        val failing = FileOps(root, journals, pages, moveOnDisk = { _, _ -> false })
        val a = pdf("Lecture.pdf")
        val journal = withAppData(a, page = 4)

        assertTrue(failing.rename(a, "Week 1") is FileOps.Result.Refused)

        assertTrue(a.exists())
        assertTrue(journal.exists())
        assertEquals(mapOf(a.path to 4), pages.map)
        assertFalse(journalOf(File(root, "Week 1.pdf")).exists())
    }

    /**
     * A real session on the file with unsaved writing (its save fails, so the writing stays in
     * the journal), closed by another thread a little later. The rename waits for that close,
     * and the writing goes with the file.
     */
    @Test fun renameWaitsForTheOpenSession() {
        val a = pdf("Lecture.pdf")
        val fake = FakePdfPages().apply { failNextSave = true }
        val session = DocumentSession(a, journals, open = { fake }, scheduler = FakeScheduler(), events = {})
        session.start()
        session.addStroke(0, dot)

        var stillThereWhenClosing = false
        val closer = Thread {
            Thread.sleep(300)
            stillThereWhenClosing = a.exists()
            session.close()
        }
        closer.start()

        assertEquals(FileOps.Result.Done, ops.rename(a, "Week 1"))
        closer.join()

        assertTrue(stillThereWhenClosing)
        val renamed = File(root, "Week 1.pdf")
        assertTrue(renamed.exists())
        assertNull(ChangeJournal.forDocument(journals, a).read())
        assertEquals(1, ChangeJournal.forDocument(journals, renamed).read()!!.changes.size)
    }

    @Test fun sessionThatNeverClosesRefuses() {
        val a = pdf("Lecture.pdf")
        val session = DocumentSession(a, journals, open = { FakePdfPages() }, scheduler = FakeScheduler(), events = {})
        session.start()
        val impatient = FileOps(root, journals, pages, sessionWaitMs = 50)

        val result = impatient.rename(a, "Week 1")

        assertEquals(FileOps.Result.Refused("'Lecture.pdf' is still saving. Try again in a moment."), result)
        assertTrue(a.exists())
        session.close()
    }

    /** Review: a sibling of the root whose name starts the same is still outside it. */
    @Test fun rootSiblingWithTheSameStartIsOutside() {
        val sibling = tmp.newFolder("Documents2")
        val there = File(sibling, "Notes.pdf").apply { writeText("x") }
        val a = pdf("Lecture.pdf")
        assertTrue(ops.rename(there, "x") is FileOps.Result.Refused)
        assertTrue(ops.delete(there) is FileOps.Result.Refused)
        assertTrue(ops.move(a, sibling) is FileOps.Result.Refused)
        assertTrue(ops.newFolder(sibling, "x") is FileOps.Result.Refused)
        assertTrue(there.exists() && a.exists())
        assertFalse(File(sibling, "x").exists())
    }

    /** A link could lead outside the library, so neither a link nor a linked folder is acted on. */
    @Test fun linksAreRefused() {
        val elsewhere = tmp.newFolder("Elsewhere")
        val real = File(elsewhere, "Real.pdf").apply { writeText("x") }
        val fileLink = Files.createSymbolicLink(File(root, "Link.pdf").toPath(), real.toPath()).toFile()
        val folderLink = Files.createSymbolicLink(File(root, "Linked").toPath(), elsewhere.toPath()).toFile()
        val a = pdf("Lecture.pdf")
        assertTrue(ops.rename(fileLink, "x") is FileOps.Result.Refused)
        assertTrue(ops.delete(fileLink) is FileOps.Result.Refused)
        assertTrue(ops.delete(folderLink) is FileOps.Result.Refused)
        assertTrue(ops.move(a, folderLink) is FileOps.Result.Refused)
        assertTrue(ops.newFolder(folderLink, "x") is FileOps.Result.Refused)
        assertTrue(real.exists() && a.exists() && fileLink.exists())
        assertEquals(listOf(real), elsewhere.listFiles()!!.toList())
    }

    /**
     * Review: after "save to a copy" the session writes into the copy, so a rename of the copy
     * must wait for that session too, not only one of the original.
     */
    @Test fun renameOfACopyWaitsForItsSession() {
        val a = pdf("Lecture.pdf")
        val events = mutableListOf<SessionEvent>()
        val fake = FakePdfPages(wasRepaired = true)
        // The copy is opened once to check it, and a copy is clean even when the original was repaired.
        val session = DocumentSession(a, journals, open = { if (it == a) fake else FakePdfPages() }, scheduler = FakeScheduler(), events = { events += it })
        session.start()
        session.addStroke(0, dot)
        session.saveRepairedToCopy()
        val copy = events.filterIsInstance<SessionEvent.SavedToCopy>().single().copy
        // Writing in the copy that its last save won't manage, so it's only in the journal.
        session.addStroke(0, dot)
        fake.failNextSave = true

        var stillThereWhenClosing = false
        val closer = Thread {
            Thread.sleep(300)
            stillThereWhenClosing = copy.exists()
            session.close()
        }
        closer.start()

        assertEquals(FileOps.Result.Done, ops.rename(copy, "Week 1"))
        closer.join()

        assertTrue(stillThereWhenClosing)
        val renamed = File(root, "Week 1.pdf")
        assertTrue(renamed.exists())
        assertEquals(1, ChangeJournal.forDocument(journals, renamed).read()!!.changes.size)
        // Both of the session's keys are gone once it closed: nothing waits on it any more.
        assertTrue(DocumentSession.awaitClosed(a, 0) && DocumentSession.awaitClosed(copy, 0))
    }

    /** A journal already waiting at the new name (from a file that's gone) is kept, never overwritten. */
    @Test fun journalAtTheNewNameIsSetAsideNotOverwritten() {
        val a = pdf("Lecture.pdf")
        withAppData(a)
        val target = File(root, "Week 1.pdf")
        val stale = journalOf(target).apply { writeText("stale writing\n") }

        assertEquals(FileOps.Result.Done, ops.rename(a, "Week 1"))

        assertEquals(1, ChangeJournal.forDocument(journals, target).read()!!.changes.size)
        val setAside = journals.listFiles()!!.filter { it.name.startsWith(stale.name + ".kept-") }
        assertEquals(listOf("stale writing\n"), setAside.map { it.readText() })
    }

    /**
     * On the memory card a rename that only changes case reports success and does nothing
     * (found on the tablet). The fake disk here does the same, so only the step through a
     * hidden name gets the new case on, and the journal and page follow it.
     */
    @Test fun renameChangingOnlyCaseGoesByAHiddenName() {
        val caseBlind = FileOps(root, journals, pages, moveOnDisk = { from, to ->
            if (from.name.equals(to.name, ignoreCase = true)) true else from.renameTo(to)
        })
        val a = pdf("lecture.pdf")
        withAppData(a, page = 2)

        assertEquals(FileOps.Result.Done, caseBlind.rename(a, "Lecture"))

        assertEquals(listOf("Lecture.pdf"), root.list()!!.toList())
        assertTrue(journalOf(File(root, "Lecture.pdf")).exists())
        assertEquals(mapOf(File(root, "Lecture.pdf").path to 2), pages.map)
    }

    @Test fun caseOnlyRenameThatFailsHalfwayPutsTheFileBack() {
        val failsSecond = FileOps(root, journals, pages, moveOnDisk = { from, to ->
            !to.name.equals("Lecture.pdf") && from.renameTo(to)
        })
        val a = pdf("lecture.pdf")
        val journal = withAppData(a, page = 2)

        assertTrue(failsSecond.rename(a, "Lecture") is FileOps.Result.Refused)

        assertEquals(listOf("lecture.pdf"), root.list()!!.toList())
        assertTrue(journal.exists())
        assertEquals(mapOf(a.path to 2), pages.map)
    }

    /** The process died between the two renames: the file is left hidden, and the next listing puts it back. */
    @Test fun strandedRenameIsRecovered() {
        val a = pdf("lecture.pdf")
        val folder = File(root, "Physics").apply { mkdir() }
        withAppData(a, page = 2)
        assertTrue(a.renameTo(File(root, ".lecture.pdf.pnotes-rename")))
        assertTrue(folder.renameTo(File(root, ".Physics.pnotes-rename")))

        val shown = FolderListing.list(root).map { it.name }

        assertEquals(listOf("Physics", "lecture"), shown)
        assertTrue(a.exists())
        assertEquals(setOf("lecture.pdf", "Physics"), root.list()!!.toSet())
        assertTrue(journalOf(a).exists())
        assertEquals(mapOf(a.path to 2), pages.map)
    }

    /** The second rename and the put-back both fail: it stays hidden, then a listing recovers it, and a later rename works. */
    @Test fun putBackFailureLeavesItRecoverable() {
        var calls = 0
        val failsAfterFirst = FileOps(root, journals, pages, moveOnDisk = { from, to -> ++calls == 1 && from.renameTo(to) })
        val a = pdf("lecture.pdf")
        val journal = withAppData(a, page = 2)

        assertTrue(failsAfterFirst.rename(a, "Lecture") is FileOps.Result.Refused)
        assertEquals(listOf(".lecture.pdf.pnotes-rename"), root.list()!!.toList())
        assertTrue(journal.exists())

        assertEquals(listOf("lecture"), FolderListing.list(root).map { it.name })
        assertEquals(listOf("lecture.pdf"), root.list()!!.toList())
        assertEquals(FileOps.Result.Done, ops.rename(a, "Week 1"))
    }

    @Test fun strandedNameDoesNotBlockTheNextRename() {
        val a = pdf("lecture.pdf")
        File(root, ".other.pdf.pnotes-rename").writeText("stranded")
        assertEquals(FileOps.Result.Done, ops.rename(a, "Week 1"))
        assertEquals(setOf("Week 1.pdf", "other.pdf"), root.list()!!.toSet())
    }

    @Test fun aFreshAttemptIsWrittenBesideTheOriginal() {
        val a = pdf("Physics/BSA Beams — workbook.pdf")
        val before = a.readBytes()
        val calls = mutableListOf<Pair<String, String>>()

        val result = ops.freshAttempt(a) { from, to -> calls += from.name to to.name; to.writeText("clean") }

        val made = File(root, "Physics/BSA Beams — workbook (attempt 2).pdf")
        assertEquals(FileOps.Result.Made(made), result)
        assertEquals(listOf("BSA Beams — workbook.pdf" to "BSA Beams — workbook (attempt 2).pdf"), calls)
        assertTrue(before.contentEquals(a.readBytes()))
        assertEquals("clean", made.readText())
    }

    /** As [renameWaitsForTheOpenSession]: the copy is made from the file as its last save left it. */
    @Test fun aFreshAttemptWaitsForTheLastSave() {
        val a = pdf("Lecture.pdf")
        val session = DocumentSession(a, journals, open = { FakePdfPages() }, scheduler = FakeScheduler(), events = {})
        session.start()
        session.addStroke(0, dot)

        val closer = Thread {
            Thread.sleep(300)
            session.close()
        }
        closer.start()

        var copiedFrom: String? = null
        val result = ops.freshAttempt(a) { from, to -> copiedFrom = from.readText(); to.writeText("clean") }
        closer.join()

        assertEquals(FileOps.Result.Made(File(root, "Lecture (attempt 2).pdf")), result)
        // The fake writes how many items it held: the stroke was saved before the copy was made.
        assertEquals("items=1", copiedFrom)
    }

    @Test fun aFolderHasNoFreshAttempt() {
        val folder = File(root, "Physics").apply { mkdir() }
        pdf("Physics/One.pdf")
        var called = false

        assertTrue(ops.freshAttempt(folder) { _, _ -> called = true } is FileOps.Result.Refused)

        assertFalse(called)
        assertEquals(listOf("One.pdf"), folder.list()!!.toList())
    }

    @Test fun onlyAPdfInTheLibraryHasAFreshAttempt() {
        val notes = File(root, "notes.txt").apply { writeText("x") }
        val outside = tmp.newFile("elsewhere.pdf")
        var called = false
        assertTrue(ops.freshAttempt(notes) { _, _ -> called = true } is FileOps.Result.Refused)
        assertTrue(ops.freshAttempt(outside) { _, _ -> called = true } is FileOps.Result.Refused)
        assertFalse(called)
        assertEquals(setOf("notes.txt"), root.list()!!.toSet())
    }

    /** A memory card ignores case, so a name taken in another case is skipped too. */
    @Test fun aFreshAttemptSkipsANameTakenInAnotherCase() {
        val a = pdf("W.pdf")
        pdf("w (ATTEMPT 2).pdf")
        assertEquals(FileOps.Result.Made(File(root, "W (attempt 3).pdf")), ops.freshAttempt(a) { _, to -> to.writeText("clean") })
    }

    /**
     * writeClean leaves a whole file or nothing (SafeSave), so after a failed write anything at
     * the new name is someone else's: a sync app's, say. It stays.
     */
    @Test fun aFileThatAppearsDuringAFailedWriteSurvives() {
        val a = pdf("Lecture.pdf")

        val result = ops.freshAttempt(a) { _, to -> to.writeText("synced"); throw IOException("storage full") }

        assertEquals(FileOps.Result.Refused("A fresh attempt of 'Lecture.pdf' couldn't be made: storage full"), result)
        assertEquals("synced", File(root, "Lecture (attempt 2).pdf").readText())
    }

    /** With the app's way of writing (SafeSave.createNew), a file arriving at the name mid-write isn't overwritten. */
    @Test fun aFileThatAppearsDuringTheWriteIsNotOverwritten() {
        val a = pdf("Lecture.pdf")
        val made = File(root, "Lecture (attempt 2).pdf")

        val result = ops.freshAttempt(a) { _, to -> SafeSave.createNew(to) { t -> made.writeText("synced"); t.writeText("clean") } }

        assertTrue(result is FileOps.Result.Refused)
        assertEquals("synced", made.readText())
        assertEquals(setOf("Lecture.pdf", made.name), root.list()!!.toSet())
    }

    /**
     * A journal left under the new name by an earlier file of that name is set aside, as a
     * rename does, so its writing is never put onto the clean copy.
     */
    @Test fun aJournalAlreadyAtTheNewNameIsSetAside() {
        val a = pdf("Lecture.pdf")
        val made = File(root, "Lecture (attempt 2).pdf")
        val stale = journalOf(made).apply { writeText("stale writing\n") }

        assertEquals(FileOps.Result.Made(made), ops.freshAttempt(a) { _, to -> to.writeText("clean") })

        assertFalse(stale.exists())
        val setAside = journals.listFiles()!!.filter { it.name.startsWith(stale.name + ".kept-") }
        assertEquals(listOf("stale writing\n"), setAside.map { it.readText() })
    }

    /** A page remembered for an earlier file of the same name mustn't open the new copy part-way through. */
    @Test fun aFreshAttemptStartsAtNoRememberedPage() {
        val a = pdf("Lecture.pdf")
        pages.map[a.path] = 4
        pages.map[File(root, "Lecture (attempt 2).pdf").path] = 9

        ops.freshAttempt(a) { _, to -> to.writeText("clean") }

        assertEquals(mapOf(a.path to 4), pages.map)
    }
}
