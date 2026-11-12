package com.fzizzle.epapernotes.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeSaveTest {
    @get:Rule val tmp = TemporaryFolder()

    private companion object {
        // Whole seconds: some file systems keep modification times to the second.
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }

    @Test fun replacesContent() {
        val f = tmp.newFile("a.pdf").apply { writeText("old") }
        SafeSave.replace(f) { it.writeText("new") }
        assertEquals("new", f.readText())
        assertFalse(SafeSave.tempFileFor(f).exists())
    }

    @Test fun failedWriteLeavesOriginalUntouched() {
        val f = tmp.newFile("a.pdf").apply { writeText("old") }
        assertThrows(IOException::class.java) {
            SafeSave.replace(f) { it.writeText("half"); throw IOException("disk full") }
        }
        assertEquals("old", f.readText())
        assertFalse(SafeSave.tempFileFor(f).exists())
    }

    @Test fun tempNameIsHidden() = assertEquals(".a.pdf.pnotes-tmp", SafeSave.tempFileFor(File("/x/a.pdf")).name)

    @Test fun recognisesTempFiles() {
        assertTrue(SafeSave.isTempFile(".a.pdf.pnotes-tmp"))
        assertFalse(SafeSave.isTempFile("a.pdf"))
    }

    @Test fun cleansStaleTempsOnly() {
        tmp.newFile(".a.pdf.pnotes-tmp").apply { setLastModified(NOW - HOUR) }
        tmp.newFile("a.pdf")
        assertEquals(1, SafeSave.cleanStale(tmp.root, now = NOW))
        assertTrue(File(tmp.root, "a.pdf").exists())
    }

    /** A save may be writing it right now (design-gaps Task 1): only temp files over ten minutes old go. */
    @Test fun aFreshTempFileIsLeftAlone() {
        val fresh = tmp.newFile(".a.pdf.pnotes-tmp").apply { setLastModified(NOW - 60_000) }
        assertEquals(0, SafeSave.cleanStale(tmp.root, now = NOW))
        assertTrue(fresh.exists())
    }

    @Test fun anOldTempFileIsRemoved() {
        val old = tmp.newFile(".a.pdf.pnotes-tmp").apply { setLastModified(NOW - SafeSave.STALE_AFTER_MS - 1000) }
        assertEquals(1, SafeSave.cleanStale(tmp.root, now = NOW))
        assertFalse(old.exists())
    }

    @Test fun otherHiddenFilesAreNeverTouched() {
        val others = listOf(".stfolder", ".nomedia", ".Lecture.pdf.swp").map { tmp.newFile(it).apply { setLastModified(NOW - HOUR) } }
        tmp.newFolder(".stversions")
        assertEquals(0, SafeSave.cleanStale(tmp.root, now = NOW))
        assertTrue(others.all { it.exists() })
    }

    @Test fun subfoldersAreNotEntered() {
        val sub = tmp.newFolder("Week 2")
        val inside = File(sub, ".b.pdf.pnotes-tmp").apply { writeText("x"); setLastModified(NOW - HOUR) }
        assertEquals(0, SafeSave.cleanStale(tmp.root, now = NOW))
        assertTrue(inside.exists())
    }

    @Test fun worksWhenTargetDoesNotExistYet() {
        val f = File(tmp.root, "copy.pdf") // plan 5 creates "(my edits …)" copies this way
        SafeSave.replace(f) { it.writeText("x") }
        assertEquals("x", f.readText())
    }

    /** Review C1: if the temp file is missing or empty when it's time to swap, the original must survive. */
    @Test fun writeThatLeavesNoTempFailsAndKeepsOriginal() {
        val f = tmp.newFile("a.pdf").apply { writeText("old") }
        assertThrows(IOException::class.java) { SafeSave.replace(f) { /* wrote nothing */ } }
        assertEquals("old", f.readText())
    }

    @Test fun writeThatLeavesEmptyTempFailsAndKeepsOriginal() {
        val f = tmp.newFile("a.pdf").apply { writeText("old") }
        assertThrows(IOException::class.java) { SafeSave.replace(f) { it.writeText("") } }
        assertEquals("old", f.readText())
    }

    /** The journal is told about the new version at this moment: finished, but not yet swapped in. */
    @Test fun beforeSwapSeesTheNewVersionAndTheOldIsStillInPlace() {
        val f = tmp.newFile("a.pdf").apply { writeText("old") }
        var seen = ""
        var inPlace = ""
        SafeSave.replace(f, beforeSwap = { seen = it.readText(); inPlace = f.readText() }) { it.writeText("new") }
        assertEquals("new", seen)
        assertEquals("old", inPlace)
        assertEquals("new", f.readText())
    }

    @Test fun aThrowingBeforeSwapLeavesTheOriginal() {
        val f = tmp.newFile("a.pdf").apply { writeText("old") }
        assertThrows(IOException::class.java) {
            SafeSave.replace(f, beforeSwap = { throw IOException("no") }) { it.writeText("new") }
        }
        assertEquals("old", f.readText())
        assertFalse(SafeSave.tempFileFor(f).exists())
    }

    /**
     * The library tidies the folder on show at every listing, and with the memory card out
     * that folder isn't there. Nothing to remove, and above all no crash: the library must
     * still come up to say the card is out.
     */
    @Test fun cleaningAFolderThatIsNotThereRemovesNothing() {
        assertEquals(0, SafeSave.cleanStale(File(tmp.root, "card-that-is-out")))
    }
}
