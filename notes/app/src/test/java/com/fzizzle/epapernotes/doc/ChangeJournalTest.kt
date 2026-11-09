package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.ImageItem
import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Box
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

class ChangeJournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val base = FileIdentity(100L, 1700000000000L, "cd".repeat(32))
    private val dot = Stroke(StrokeKind.PEN, listOf(StrokePoint(1f, 2f, 0.5f)))
    private val line = Stroke(StrokeKind.HIGHLIGHTER, listOf(StrokePoint(1f, 2f, 1f), StrokePoint(3f, 4f, 1f)))

    private fun add(page: Int, stroke: Stroke) = AddItems(page, listOf(InkItem("id$page", stroke)))

    private fun journal() = ChangeJournal(File(tmp.root, "j.journal"))

    @Test fun appendedStrokesReadBack() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        j.append(add(3, line))
        assertEquals(listOf(add(0, dot), add(3, line)), j.read()!!.changes)
    }

    /** A power cut can cut the last line short. The complete lines must still come back. */
    @Test fun tornLastLineIsIgnored() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        j.append(add(1, dot))
        j.file.appendText("add 2 pn1 pen 1.00,2.0")
        assertEquals(2, j.read()!!.changes.size)
    }

    @Test fun missingFileReadsNull() = assertNull(journal().read())

    @Test fun startReplacesOldContent() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        j.start(base)
        assertEquals(0, j.read()!!.changes.size)
    }

    @Test fun clearDeletes() {
        val j = journal()
        j.start(base)
        j.clear()
        assertFalse(j.file.exists())
    }

    @Test fun journalNamesDifferPerPdf() = assertNotEquals(
        ChangeJournal.forDocument(tmp.root, File("/sdcard/a.pdf")).file.name,
        ChangeJournal.forDocument(tmp.root, File("/sdcard/b.pdf")).file.name,
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
        j.append(add(0, dot))
        j.file.appendText("add 1 pn1 hl 1.00,2.00,1.000")
        assertEquals(listOf(add(0, dot)), j.read()!!.changes)
    }

    @Test fun readsPlan2Journal() {
        val j = journal()
        j.file.writeText("pnj1 ${base.encode()}\nadd 3 pn1 pen 1.00,2.00,0.500\n")
        val change = j.read()!!.changes.single() as AddItems
        assertEquals(3, change.page)
        assertEquals(Stroke(StrokeKind.PEN, listOf(StrokePoint(1f, 2f, 0.5f))), (change.items.single() as InkItem).stroke)
    }

    @Test fun tornImageLineIsIgnored() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        val img = AddItems(1, listOf(ImageItem("i1", Box(0f, 0f, 5f, 5f), 0, ByteArray(200) { it.toByte() })))
        val text = ChangeCodec.encode(img)
        j.file.appendText(text.substring(0, text.length / 2))
        assertEquals(listOf(add(0, dot)), j.read()!!.changes)
    }

    @Test fun allKindsSurviveTheFile() {
        val j = journal()
        j.start(base)
        val item = InkItem("a", dot)
        val all = listOf(AddItems(0, listOf(item)), RemoveItems(0, listOf(item)), ReplaceItems(0, listOf(item), listOf(item)), RotatePages(mapOf(0 to 0), mapOf(0 to 90)))
        all.forEach { j.append(it) }
        assertEquals(all, j.read()!!.changes)
    }
}
