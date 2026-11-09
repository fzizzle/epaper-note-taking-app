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
        tmp.newFile(".a.pdf.pnotes-tmp")
        tmp.newFile("a.pdf")
        assertEquals(1, SafeSave.cleanStale(tmp.root))
        assertTrue(File(tmp.root, "a.pdf").exists())
    }

    @Test fun worksWhenTargetDoesNotExistYet() {
        val f = File(tmp.root, "copy.pdf") // plan 5 creates "(my edits …)" copies this way
        SafeSave.replace(f) { it.writeText("x") }
        assertEquals("x", f.readText())
    }
}
