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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    /** A page change notes itself before its save, and a journal rewritten after the save has no such note. */
    @Test fun renumberingLineIsReadBackAndGoesWithARewrite() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        assertFalse(j.read()!!.renumbering)
        j.markRenumbering()
        j.append(add(2, line)) // written after it, with the new numbers
        val read = j.read()!!
        assertTrue(read.renumbering)
        assertEquals(listOf(add(0, dot), add(2, line)), read.changes)
        j.rewrite(base, read.changes)
        assertFalse(j.read()!!.renumbering)
    }

    /** Review 2.1: the journal notes a save just before the PDF is swapped in, and how many changes it holds. */
    @Test fun savingLineIsReadBack() {
        val j = journal()
        val saved = FileIdentity(200L, 1700000001000L, "ab".repeat(32))
        j.start(base)
        j.append(add(0, dot))
        j.append(add(1, dot))
        j.markSaving(saved, 2)
        j.append(add(2, line)) // written while the save ran
        val read = j.read()!!
        assertEquals(listOf(add(0, dot), add(1, dot), add(2, line)), read.changes)
        assertEquals(PendingSave(saved, 2), read.pendingSave)
        assertEquals(base, read.base)
    }

    @Test fun noSavingLineMeansNoPendingSave() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        assertNull(j.read()!!.pendingSave)
    }

    /** Cut short it could still look whole, with a smaller count (1 instead of 12 here). It must be dropped. */
    @Test fun aCutShortSavingLineIsIgnored() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        j.file.appendText("saving 200 1700000001000 ${"ab".repeat(32)} 1")
        val read = j.read()!!
        assertNull(read.pendingSave)
        assertEquals(listOf(add(0, dot)), read.changes)
    }

    /** A new journal after a save has no saving line: that save is over. */
    @Test fun rewriteDropsTheSavingLine() {
        val j = journal()
        j.start(base)
        j.markSaving(base, 0)
        j.rewrite(base, listOf(add(0, dot)))
        assertNull(j.read()!!.pendingSave)
    }

    // A snip's picture goes into the journal once (review 4.6). Until then every line that
    // mentioned a snip (pasted, moved, erased, undone) carried the whole picture.

    private fun snip(id: String, x: Float = 0f, png: ByteArray = ByteArray(300) { (it * 7).toByte() }) =
        ImageItem(id, Box(x, 0f, x + 100f, 100f), 0, png)

    /** Adds [snip] on page 0, moves it [times] times, and returns every change in order. */
    private fun addAndMove(j: ChangeJournal, snip: ImageItem, times: Int): List<Change> {
        val changes = ArrayList<Change>()
        changes += AddItems(0, listOf(snip))
        var at = snip
        repeat(times) {
            val next = at.copy(box = Box(it + 1f, 0f, it + 101f, 100f))
            changes += ReplaceItems(0, listOf(at), listOf(next))
            at = next
        }
        changes.forEach { j.append(it) }
        return changes
    }

    private fun pictureLines(j: ChangeJournal) = j.file.readLines().count { it.startsWith("img ") }

    /** Review focus 4. Before: 5,600,927 bytes for this (21 copies of the picture). */
    @Test fun aMovedSnipIsJournaledOnce() {
        val j = journal()
        j.start(base)
        addAndMove(j, snip("s1", png = ByteArray(200_000) { (it * 31 + it / 7).toByte() }), times = 10)
        println("a 200 KB snip added and moved ten times: ${j.file.length()} journal bytes")
        assertTrue("${j.file.length()} bytes", j.file.length() < 400_000)
        assertEquals(1, pictureLines(j))
    }

    @Test fun replayRebuildsAMovedSnip() {
        val j = journal()
        j.start(base)
        val snip = snip("s1")
        val changes = addAndMove(j, snip, times = 10)
        val read = ChangeJournal(j.file).read()!!.changes // as the next opening would, after a kill
        assertEquals(changes, read)
        val last = (read.last() as ReplaceItems).after.single() as ImageItem
        assertTrue(snip.png.contentEquals(last.png))
        assertEquals(Box(10f, 0f, 110f, 100f), last.box)
    }

    /** Erase, undo and redo name the picture too, and two snips can hold the same picture. */
    @Test fun everyKindOfChangeRefersToThePicture() {
        val j = journal()
        j.start(base)
        val a = snip("s1")
        val copy = snip("s2", x = 200f) // pasted twice: the same picture under another id
        val other = snip("s3", png = byteArrayOf(9, 8, 7))
        val all = listOf(
            AddItems(0, listOf(a, InkItem("k", dot))), RemoveItems(0, listOf(a)), AddItems(0, listOf(a)),
            AddItems(1, listOf(copy, other)), ReplaceItems(1, listOf(copy, other), listOf(other, copy)),
        )
        all.forEach { j.append(it) }
        assertEquals(all, j.read()!!.changes)
        assertEquals(2, pictureLines(j))
    }

    /** After a save the journal is written afresh with what the save didn't hold: its pictures must come along. */
    @Test fun rewriteKeepsPicturesStillReferredTo() {
        val j = journal()
        j.start(base)
        val gone = snip("s1", png = byteArrayOf(1, 2, 3))
        val kept = snip("s2", png = byteArrayOf(4, 5, 6))
        j.append(AddItems(0, listOf(gone)))
        val rest = addAndMove(j, kept, times = 2).drop(1) // the paste itself was saved; the moves weren't
        j.rewrite(base, rest)
        assertEquals(rest, j.read()!!.changes)
        assertEquals(1, pictureLines(j)) // once, and the saved snip's picture isn't carried along

        // And what's appended next knows which pictures the new file holds.
        val more = listOf(RemoveItems(0, listOf(kept)), AddItems(0, listOf(gone)))
        more.forEach { j.append(it) }
        assertEquals(rest + more, j.read()!!.changes)
        assertEquals(2, pictureLines(j))
    }

    @Test fun startAndClearForgetWhichPicturesWereWritten() {
        val j = journal()
        val snip = snip("s1")
        j.start(base)
        j.append(AddItems(0, listOf(snip)))
        j.start(base)
        j.append(AddItems(0, listOf(snip)))
        assertEquals(listOf<Change>(AddItems(0, listOf(snip))), j.read()!!.changes)
        j.clear()
        j.start(base)
        j.append(RemoveItems(0, listOf(snip)))
        assertEquals(listOf<Change>(RemoveItems(0, listOf(snip))), j.read()!!.changes)
    }

    /**
     * Review focus 5. This text was written by the build before pictures were named: every
     * line holds its picture in full. It must read exactly as it did.
     */
    @Test fun journalsFromBeforeStillRead() {
        val j = journal()
        j.file.writeText(
            """
            pnj2 100 1700000000000 cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd
            add 0 ink k1 pn2 pen 1.000 1.00,2.00,0.500 3.00,4.00,0.750
            add 2 img s1 10.00,20.00,110.00,70.00 1 +QQPGiUwO0ZRXGdyfYiTnqm0v8rV4Ov2
            put 2 img s1 10.00,20.00,110.00,70.00 1 +QQPGiUwO0ZRXGdyfYiTnqm0v8rV4Ov2 || img s1 15.00,25.00,115.00,75.00 1 +QQPGiUwO0ZRXGdyfYiTnqm0v8rV4Ov2
            del 2 img s1 15.00,25.00,115.00,75.00 1 +QQPGiUwO0ZRXGdyfYiTnqm0v8rV4Ov2
            add 2 img s1 15.00,25.00,115.00,75.00 1 +QQPGiUwO0ZRXGdyfYiTnqm0v8rV4Ov2
            saving 200 1700000001000 abababababababababababababababababababababababababababababababab 3
            rot 2=0 || 2=90
            """.trimIndent() + "\n",
        )
        val snip = ImageItem("s1", Box(10f, 20f, 110f, 70f), 1, ByteArray(24) { (it * 11 - 7).toByte() })
        val moved = snip.copy(box = Box(15f, 25f, 115f, 75f))
        val ink = InkItem("k1", Stroke(StrokeKind.PEN, listOf(StrokePoint(1f, 2f, 0.5f), StrokePoint(3f, 4f, 0.75f))))
        val read = j.read()!!
        assertEquals(
            listOf(
                AddItems(0, listOf(ink)), AddItems(2, listOf(snip)), ReplaceItems(2, listOf(snip), listOf(moved)),
                RemoveItems(2, listOf(moved)), AddItems(2, listOf(moved)), RotatePages(mapOf(2 to 0), mapOf(2 to 90)),
            ),
            read.changes,
        )
        assertEquals(base, read.base)
        assertEquals(PendingSave(FileIdentity(200L, 1700000001000L, "ab".repeat(32)), 3), read.pendingSave)

        // The session writes such a journal afresh before adding to it. If it didn't, the new
        // lines must still sit well beside the old ones.
        j.append(ReplaceItems(2, listOf(moved), listOf(snip)))
        assertEquals(7, j.read()!!.changes.size)
        assertEquals(ReplaceItems(2, listOf(moved), listOf(snip)), j.read()!!.changes.last())
    }

    /** A power cut while the picture's line is being written: the change that needed it can't be there either. */
    @Test fun aCutShortImgLineLosesOnlyWhatFollows() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        val before = j.file.readText()
        val snip = snip("s1")
        j.append(AddItems(1, listOf(snip)))
        val added = j.file.readText().removePrefix(before)
        assertTrue(added, added.startsWith("img ") && added.count { it == '\n' } == 2)
        // Cut at every point of that one write: only the stroke before it comes back, until the
        // write is whole. In particular the picture's line alone, complete, is not a change.
        for (cut in 0 until added.length) {
            j.file.writeText(before + added.substring(0, cut))
            assertEquals("cut at $cut", listOf(add(0, dot)), journal().read()!!.changes)
        }
        j.file.writeText(before + added)
        assertEquals(listOf(add(0, dot), AddItems(1, listOf(snip))), journal().read()!!.changes)
    }

    /** It can't happen while lines are written in order, but if it does: that change is dropped, the rest read. */
    @Test fun aChangeWhosePictureIsMissingIsDropped() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        j.append(AddItems(1, listOf(snip("s1"))))
        j.append(add(2, line))
        // The picture's line damaged (its name no longer fits its contents), then gone altogether.
        val lines = j.file.readLines()
        val damaged = lines.map { if (it.startsWith("img ")) it.dropLast(8) + "AAAAAAAA" else it }
        j.file.writeText(damaged.joinToString("\n", postfix = "\n"))
        assertEquals(listOf(add(0, dot), add(2, line)), j.read()!!.changes)
        j.file.writeText(lines.filterNot { it.startsWith("img ") }.joinToString("\n", postfix = "\n"))
        assertEquals(listOf(add(0, dot), add(2, line)), j.read()!!.changes)
    }

    /** The saving line counts changes ("the first 2 are in the PDF"). A picture's line isn't one. */
    @Test fun aSavingMarkerStillCountsChangesNotPictureLines() {
        val j = journal()
        val saved = FileIdentity(200L, 1700000001000L, "ab".repeat(32))
        j.start(base)
        val changes = addAndMove(j, snip("s1"), times = 1) + AddItems(1, listOf(snip("s2", png = byteArrayOf(5, 5, 5))))
        j.append(changes.last())
        j.markSaving(saved, 2)
        val read = j.read()!!
        assertEquals(2, pictureLines(j)) // two picture lines among the three changes
        assertEquals(changes, read.changes)
        assertEquals(PendingSave(saved, 2), read.pendingSave)
        // As the next opening does after a kill: what the save didn't hold, onto the saved file.
        val rest = read.changes.drop(read.pendingSave!!.count)
        j.rewrite(saved, rest)
        assertEquals(listOf(changes[2]), j.read()!!.changes)
    }

    /** After a kill, the next session is a new object on the old file. It finds out what the file holds. */
    @Test fun aSecondJournalObjectOnTheSameFileDoesNotLoseOrDoubleAPicture() {
        val first = journal()
        first.start(base)
        val snip = snip("s1")
        val other = snip("s2", png = byteArrayOf(4, 2))
        val before = addAndMove(first, snip, times = 1)

        val second = journal()
        val after = listOf(RemoveItems(0, listOf(snip)), AddItems(1, listOf(other)), RemoveItems(1, listOf(other)))
        after.forEach { second.append(it) }
        assertEquals(before + after, journal().read()!!.changes) // nothing lost
        assertEquals(2, pictureLines(second)) // and nothing written twice

        // The first object doesn't know of the second's picture. It writes it again, which is harmless.
        first.append(AddItems(1, listOf(other)))
        assertEquals(before + after + AddItems(1, listOf(other)), journal().read()!!.changes)
    }

    /**
     * A write that failed (storage full) can leave a cut-short line at the end. What's written
     * next must not be joined onto it, or a picture's line could be spoiled while later lines
     * count on it.
     */
    @Test fun anAppendAfterACutShortLineIsNotJoinedToIt() {
        val j = journal()
        j.start(base)
        j.append(add(0, dot))
        val snip = snip("s1")
        j.file.appendText("img 00 AAAA") // no newline: cut short
        val next = journal() // doesn't know what the file holds, so it looks
        next.append(AddItems(1, listOf(snip)))
        next.append(RemoveItems(1, listOf(snip)))
        assertEquals(listOf(add(0, dot), AddItems(1, listOf(snip)), RemoveItems(1, listOf(snip))), next.read()!!.changes)
    }

    /** Every mention of a picture read back is the same bytes in memory, not a copy each. */
    @Test fun aPictureReadBackIsHeldOnce() {
        val j = journal()
        j.start(base)
        addAndMove(j, snip("s1"), times = 2)
        val pngs = j.read()!!.changes.flatMap {
            when (it) { is AddItems -> it.items; is ReplaceItems -> it.before + it.after; else -> emptyList() }
        }.map { (it as ImageItem).png }
        assertEquals(5, pngs.size)
        pngs.forEach { assertSame(pngs[0], it) }
    }
}
