package com.fzizzle.epapernotes.library

import com.fzizzle.epapernotes.storage.SafeSave
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The library folder; [tmp] itself holds it and anything "outside the library". */
    private val lib by lazy { File(tmp.root, "lib").apply { mkdirs() } }
    private val store = MemoryIndexStore()
    private var now = 0L

    /** A folder time the tests set by hand, so the 2 s rule is under their control. */
    private val t = 1_700_000_000_000L

    /** [FileIndexDisk], noting which folders were listed. */
    private inner class CountingDisk : IndexDisk {
        val inner = FileIndexDisk()
        val reads = mutableListOf<String>()
        val timesAsked = mutableListOf<String>()
        override fun timeOf(folder: File) = inner.timeOf(folder).also { timesAsked += pathOf(folder) }
        override fun read(folder: File) = inner.read(folder)?.also { reads += pathOf(folder) }
    }

    private var disk = CountingDisk()

    private fun check(useDisk: IndexDisk = disk, batch: Int = 50) = IndexCheck(lib, store, useDisk, { now }, batch = batch)

    private fun pdf(path: String) = File(lib, path).apply { parentFile.mkdirs(); writeText("x") }

    private fun dir(path: String) = if (path.isEmpty()) lib else File(lib, path)

    private fun pathOf(folder: File) = folder.relativeTo(lib).invariantSeparatorsPath

    /** Every folder of the library given folder time [time] (making files changes them). */
    private fun settle(time: Long = t) {
        lib.walkTopDown().filter { it.isDirectory }.forEach { it.setLastModified(time) }
    }

    private fun find(vararg words: String) = store.search(words.toList(), 100).hits

    /** A library 3 folders deep with 5 PDFs, read in full once with every folder time trusted. */
    private fun builtLibrary() {
        pdf("loose.pdf"); pdf("Course/Week 1.pdf"); pdf("Course/Topic/Week 2.pdf")
        pdf("Course/Topic/Deep/notes.pdf"); pdf("Course/Topic/Deep/answers.pdf")
        settle()
        now = t + 10_000
        assertEquals(CheckResult.Done(4), check().run(full = true))
        disk.reads.clear()
    }

    @Test fun aFirstFullReadFindsEveryPdf() {
        builtLibrary()
        assertEquals(listOf(Hit("Course", "Week 1.pdf"), Hit("Course/Topic", "Week 2.pdf")), find("week"))
        assertEquals(5, store.pdfs().size)
        assertTrue(store.complete)
    }

    @Test fun anUnchangedFolderIsNotListedAgain() {
        builtLibrary()
        now = t + 20_000
        assertEquals(CheckResult.Done(0), check().run(full = false))
        assertEquals(emptyList<String>(), disk.reads)
    }

    @Test fun aCheckThatIsNotFullLeavesCompleteAsItWas() {
        pdf("a.pdf"); settle(); now = t + 10_000
        check().run(full = false)
        assertFalse(store.complete)
    }

    @Test fun aChangedFolderIsListedAndItsPdfsReplaced() {
        builtLibrary()
        pdf("Course/Topic/Week 3.pdf")
        File(lib, "Course/Topic/Week 2.pdf").delete()
        dir("Course/Topic").setLastModified(t + 5_000)
        now = t + 20_000
        assertEquals(CheckResult.Done(1), check().run(full = false))
        assertEquals(listOf("Course/Topic"), disk.reads)
        assertEquals(listOf(Hit("Course", "Week 1.pdf"), Hit("Course/Topic", "Week 3.pdf")), find("week"))
    }

    @Test fun aFolderReadTooSoonAfterItsTimeIsListedAgain() {
        pdf("Course/Week 1.pdf"); settle()
        now = t + 1_000
        check().run(full = true)
        disk.reads.clear()
        now = t + 20_000
        check().run(full = false)
        assertEquals(listOf("", "Course"), disk.reads)
        // Read 20 s after its time, it is trusted from now on.
        disk.reads.clear()
        check().run(full = false)
        assertEquals(emptyList<String>(), disk.reads)
    }

    @Test fun aFolderRenamedOnALaptopMovesInTheIndex() {
        pdf("A/a.pdf"); (1..3).forEach { pdf("A/Sub/page $it.pdf") }
        settle(); now = t + 10_000
        check().run(full = true)
        assertTrue(dir("A/Sub").renameTo(dir("A/Moved")))
        dir("A").setLastModified(t + 5_000)
        now = t + 20_000
        check().run(full = false)
        assertEquals((1..3).map { Hit("A/Moved", "page $it.pdf") }, find("page"))
        assertFalse("A/Sub" in store.paths())
    }

    @Test fun aFolderGoneWithoutItsParentsTimeMovingIsDropped() {
        pdf("A/keep.pdf"); pdf("A/Sub/x.pdf")
        settle(); now = t + 10_000
        check().run(full = true)
        dir("A/Sub").deleteRecursively()
        dir("A").setLastModified(t)
        now = t + 20_000
        check().run(full = false)
        assertEquals(setOf("", "A"), store.paths())
        assertEquals(listOf(Hit("A", "keep.pdf")), store.pdfs())
    }

    @Test fun aMissingRootChangesNothing() {
        builtLibrary()
        lib.deleteRecursively()
        assertEquals(CheckResult.RootMissing, check().run(full = false))
        assertEquals(2, find("week").size)
        assertEquals(5, store.pdfs().size)
    }

    /** [FileIndexDisk], except that the library folder's time turns null once [readsBefore] folders have been listed. */
    private inner class RootGoesMissingDisk(private val readsBefore: Int) : IndexDisk {
        private val inner = FileIndexDisk()
        private var reads = 0
        override fun timeOf(folder: File) = if (folder == lib && reads >= readsBefore) null else inner.timeOf(folder)
        override fun read(folder: File) = inner.read(folder).also { reads++ }
    }

    @Test fun aRootThatGoesMissingMidCheckDropsNothing() {
        builtLibrary()
        val paths = store.paths()
        val pdfs = store.pdfs()
        assertEquals(CheckResult.RootMissing, check(RootGoesMissingDisk(readsBefore = 2), batch = 1).run(full = true))
        assertEquals(paths, store.paths())
        assertEquals(pdfs, store.pdfs())
    }

    /** Within one batch, the library folder is looked for again before a vanished folder is dropped. */
    @Test fun aRootThatGoesMissingBeforeADropDropsNothing() {
        builtLibrary()
        dir("Course/Topic/Deep").deleteRecursively()
        assertEquals(CheckResult.RootMissing, check(RootGoesMissingDisk(readsBefore = 2), batch = 50).run(full = true))
        assertTrue("Course/Topic/Deep" in store.paths())
        assertEquals(5, store.pdfs().size)
    }

    /** Lists [readsBefore] folders, then is a card taken out: no folder is there any more, the library folder included. */
    private class CardOutDisk(private val readsBefore: Int) : IndexDisk {
        private val inner = FileIndexDisk()
        private var reads = 0
        override fun timeOf(folder: File) = if (reads >= readsBefore) null else inner.timeOf(folder)
        override fun read(folder: File) = if (reads >= readsBefore) null else inner.read(folder).also { reads++ }
    }

    /** A folder not found is dropped only once the library folder is known to be there. */
    @Test fun aCardTakenOutMidCheckDropsNothing() {
        builtLibrary()
        val paths = store.paths()
        assertEquals(CheckResult.RootMissing, check(CardOutDisk(readsBefore = 2), batch = 50).run(full = true))
        assertEquals(paths, store.paths())
        assertEquals(5, store.pdfs().size)
    }

    @Test fun hiddenTemporaryAndLinkedAreLeftOut() {
        pdf("real.pdf"); pdf("Shout.PDF"); pdf(".hidden.pdf"); pdf("notes.txt"); pdf(".git/a.pdf")
        SafeSave.tempFileFor(File(lib, "draft.pdf")).writeText("x")
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        File(outside, "out.pdf").writeText("x")
        Files.createSymbolicLink(File(lib, "Linked").toPath(), outside.toPath())
        Files.createSymbolicLink(File(lib, "linked.pdf").toPath(), File(outside, "out.pdf").toPath())
        check().run(full = true)
        assertEquals(setOf(""), store.paths())
        assertEquals(listOf(Hit("", "real.pdf"), Hit("", "Shout.PDF")), store.pdfs())
    }

    /** A PDF added to a folder whose time is then put back, which the check can't see. */
    private fun aHiddenChange() {
        builtLibrary()
        pdf("Course/Topic/Week 9.pdf")
        dir("Course/Topic").setLastModified(t)
        now = t + 20_000
    }

    @Test fun rereadPicksUpAChangeWithoutAFolderTimeMoving() {
        aHiddenChange()
        assertEquals(CheckResult.Done(0), check().run(full = false))
        assertEquals(2, find("week").size)
        disk.reads.clear(); disk.timesAsked.clear()
        check().reread(listOf(dir("Course/Topic")))
        assertEquals(Hit("Course/Topic", "Week 9.pdf"), find("week", "9").single())
        // Only that folder: a re-read after every listing must not become a check of all under it.
        assertEquals(listOf("Course/Topic"), disk.reads)
        assertFalse("Course/Topic/Deep" in disk.timesAsked)
    }

    @Test fun aFullRunTrustsNoFolderTime() {
        aHiddenChange()
        assertEquals(CheckResult.Done(4), check().run(full = true))
        assertEquals(Hit("Course/Topic", "Week 9.pdf"), find("week", "9").single())
    }

    @Test fun rereadDropsVanishedFoldersAndReadsNewOnesInFull() {
        builtLibrary()
        dir("Course/Topic/Deep").deleteRecursively()
        pdf("Course/Topic/New/Inner/fresh.pdf")
        check().reread(listOf(dir("Course/Topic")))
        assertEquals(listOf("Course/Topic", "Course/Topic/New", "Course/Topic/New/Inner"), disk.reads)
        assertEquals(setOf("", "Course", "Course/Topic", "Course/Topic/New", "Course/Topic/New/Inner"), store.paths())
        assertEquals(Hit("Course/Topic/New/Inner", "fresh.pdf"), find("fresh").single())
    }

    @Test fun rereadDropsAFolderNoLongerThereAndIgnoresOnesOutside() {
        builtLibrary()
        dir("Course/Topic").deleteRecursively()
        val outside = File(tmp.root, "outside").apply { mkdirs() }
        File(outside, "Week 5.pdf").writeText("x")
        check().reread(listOf(dir("Course/Topic"), outside))
        assertEquals(setOf("", "Course"), store.paths())
        assertEquals(listOf(Hit("Course", "Week 1.pdf")), find("week"))
    }
}
