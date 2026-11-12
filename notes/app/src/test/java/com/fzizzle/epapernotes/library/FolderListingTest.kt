package com.fzizzle.epapernotes.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderListingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun names(folder: File) = FolderListing.list(folder).map { it.name }

    @Test fun foldersComeFirst() {
        tmp.newFile("a.pdf"); tmp.newFolder("Zed"); tmp.newFile("b.pdf"); tmp.newFolder("alpha")
        assertEquals(listOf("alpha", "Zed", "a", "b"), names(tmp.root))
    }

    @Test fun naturalNumbers() {
        tmp.newFile("Lecture 10.pdf"); tmp.newFile("Lecture 2.pdf")
        assertEquals(listOf("Lecture 2", "Lecture 10"), names(tmp.root))
    }

    @Test fun hiddenAndTempFilesAreLeftOut() {
        tmp.newFile("a.pdf"); tmp.newFile(".hidden.pdf"); tmp.newFile(".a.pdf.notes-tmp"); tmp.newFolder(".git")
        assertEquals(listOf("a"), names(tmp.root))
    }

    @Test fun onlyPdfsAreShown() {
        tmp.newFile("a.PDF"); tmp.newFile("b.txt"); tmp.newFile("c.pdf.bak")
        val list = FolderListing.list(tmp.root)
        assertEquals(listOf("a"), list.map { it.name })
        assertTrue(list[0] is PdfEntry)
    }

    @Test fun pdfEntryCarriesSizeAndTime() {
        val f = tmp.newFile("a.pdf").apply { writeText("12345"); setLastModified(1_700_000_000_000) }
        val e = FolderListing.list(tmp.root).single() as PdfEntry
        assertEquals(f, e.file)
        assertEquals(5L, e.size)
        assertEquals(f.lastModified(), e.modified)
    }

    @Test fun pdfCountIsDirectChildrenOnly() {
        val sub = tmp.newFolder("sub")
        File(sub, "one.pdf").createNewFile(); File(sub, "TWO.PDF").createNewFile()
        File(sub, "x.txt").createNewFile(); File(sub, ".hid.pdf").createNewFile()
        File(sub, "deeper").mkdir(); File(sub, "deeper/three.pdf").createNewFile()
        val e = FolderListing.list(tmp.root).single() as FolderEntry
        assertEquals(2, e.pdfCount)
        assertEquals(1, e.folderCount)
    }

    @Test fun folderCountIsVisibleDirectSubfoldersOnly() {
        val sub = tmp.newFolder("course")
        File(sub, "Week 1").mkdir(); File(sub, "Week 2").mkdir(); File(sub, ".git").mkdir()
        File(sub, "Week 1/deeper").mkdir(); File(sub, "a.txt").createNewFile()
        val e = FolderListing.list(tmp.root).single() as FolderEntry
        assertEquals(0, e.pdfCount)
        assertEquals(2, e.folderCount)
    }

    @Test fun oddNamesAreListed() {
        val long = "n".repeat(196)
        for (n in listOf("with space.pdf", "café.pdf", "v1.2 notes.pdf", "$long.pdf")) tmp.newFile(n)
        assertEquals(setOf("with space", "café", "v1.2 notes", long), names(tmp.root).toSet())
    }

    @Test fun unreadableFolderIsEmpty() {
        // A path that isn't a directory has listFiles() == null, on any user.
        assertEquals(emptyList<Entry>(), FolderListing.list(tmp.newFile("plain.txt")))
        assertEquals(emptyList<Entry>(), FolderListing.list(File(tmp.root, "missing")))
    }

    private fun pdfIn(folder: File, name: String) = File(folder, name).apply { createNewFile() }

    private fun opened(folder: File) = FolderListing.list(folder, openOutUpTo = FolderListing.OPEN_OUT_UP_TO)

    /** A topic folder holding just a workbook and its answer key: shown as the two PDFs, where the folder was. */
    @Test fun aFolderOfTwoPdfsIsOpenedOut() {
        tmp.newFolder("Alpha")
        val beams = tmp.newFolder("Beams")
        pdfIn(beams, "workbook.pdf"); pdfIn(beams, "answer key.pdf")
        tmp.newFolder("Zeta"); tmp.newFile("loose.pdf")
        val list = opened(tmp.root)
        assertEquals(listOf("Alpha", "answer key", "workbook", "Zeta", "loose"), list.map { it.name })
        assertEquals(listOf(null, beams, beams, null, null), list.map { (it as? PdfEntry)?.group })
        assertEquals(File(beams, "workbook.pdf"), list[2].file)
    }

    @Test fun aFolderWithASubfolderStaysAFolder() {
        val sub = tmp.newFolder("Course")
        pdfIn(sub, "a.pdf"); File(sub, "Week 1").mkdir()
        assertTrue(opened(tmp.root).single() is FolderEntry)
    }

    @Test fun anEmptyFolderStaysAFolder() {
        tmp.newFolder("Empty")
        assertTrue(opened(tmp.root).single() is FolderEntry)
    }

    @Test fun aFourthPdfClosesTheFolderAgain() {
        val sub = tmp.newFolder("Topic")
        repeat(3) { pdfIn(sub, "p$it.pdf") }
        assertEquals(3, opened(tmp.root).size)
        pdfIn(sub, "p3.pdf")
        val closed = opened(tmp.root).single() as FolderEntry
        assertEquals(4, closed.pdfCount)
    }

    @Test fun hiddenAndTemporaryFilesDontCount() {
        val sub = tmp.newFolder("Topic")
        pdfIn(sub, "a.pdf"); pdfIn(sub, ".hidden.pdf"); pdfIn(sub, ".a.pdf.notes-tmp"); File(sub, ".stfolder").mkdir(); pdfIn(sub, "notes.txt")
        val list = opened(tmp.root)
        assertEquals(listOf("a"), list.map { it.name })
        assertEquals(sub, (list.single() as PdfEntry).group)
    }

    @Test fun withZeroNothingIsOpenedOut() {
        val sub = tmp.newFolder("Topic")
        pdfIn(sub, "a.pdf")
        assertTrue(FolderListing.list(tmp.root).single() is FolderEntry)
    }

    @Test fun sameNamesInTwoFoldersStayApart() {
        val a = tmp.newFolder("A"); val b = tmp.newFolder("B")
        pdfIn(a, "workbook.pdf"); pdfIn(b, "workbook.pdf")
        val list = opened(tmp.root).map { it as PdfEntry }
        assertEquals(2, list.toSet().size)
        assertEquals(listOf(File(a, "workbook.pdf"), File(b, "workbook.pdf")), list.map { it.file })
        assertEquals(listOf(a, b), list.map { it.group })
    }
}
