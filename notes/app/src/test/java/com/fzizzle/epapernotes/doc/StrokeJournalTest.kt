package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import com.fzizzle.epapernotes.storage.FileIdentity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StrokeJournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val base = FileIdentity(100L, 1700000000000L, "cd".repeat(32))
    private val dot = Stroke(StrokeKind.PEN, listOf(StrokePoint(1f, 2f, 0.5f)))
    private val line = Stroke(StrokeKind.HIGHLIGHTER, listOf(StrokePoint(1f, 2f, 1f), StrokePoint(3f, 4f, 1f)))

    private fun journal() = StrokeJournal(File(tmp.root, "j.journal"))

    @Test fun appendedStrokesReadBack() {
        val j = journal()
        j.start(base)
        j.append(0, dot)
        j.append(3, line)
        assertEquals(listOf(PendingStroke(0, dot), PendingStroke(3, line)), j.read()!!.strokes)
    }

    /** A power cut can cut the last line short. The complete lines must still come back. */
    @Test fun tornLastLineIsIgnored() {
        val j = journal()
        j.start(base)
        j.append(0, dot)
        j.append(1, dot)
        j.file.appendText("add 2 pn1 pen 1.00,2.0")
        assertEquals(2, j.read()!!.strokes.size)
    }

    @Test fun missingFileReadsNull() = assertNull(journal().read())

    @Test fun startReplacesOldContent() {
        val j = journal()
        j.start(base)
        j.append(0, dot)
        j.start(base)
        assertEquals(0, j.read()!!.strokes.size)
    }

    @Test fun clearDeletes() {
        val j = journal()
        j.start(base)
        j.clear()
        assertFalse(j.file.exists())
    }

    @Test fun journalNamesDifferPerPdf() = assertNotEquals(
        StrokeJournal.forDocument(tmp.root, File("/sdcard/a.pdf")).file.name,
        StrokeJournal.forDocument(tmp.root, File("/sdcard/b.pdf")).file.name,
    )

    @Test fun baseIdentityIsKept() {
        val j = journal()
        j.start(base)
        assertEquals(base, j.read()!!.base)
    }

    /** Cut exactly between two points, the line still parses, as a shorter, wrong stroke. It must be dropped. */
    @Test fun lineCutBetweenPointsIsIgnored() {
        val j = journal()
        j.start(base)
        j.append(0, dot)
        j.file.appendText("add 1 pn1 hl 1.00,2.00,1.000")
        assertEquals(listOf(PendingStroke(0, dot)), j.read()!!.strokes)
    }
}
