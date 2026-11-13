package com.fzizzle.epapernotes.doc

import com.fzizzle.epapernotes.ink.InkItem
import com.fzizzle.epapernotes.ink.Stroke
import com.fzizzle.epapernotes.ink.StrokeKind
import com.fzizzle.epapernotes.ink.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fake renumbers pages as the real PDF does, so the session's tests can trust it. */
class FakePdfPagesTest {
    private fun item(id: String) = InkItem(id, Stroke(StrokeKind.PEN, listOf(StrokePoint(1f, 1f, 0.5f))))

    @Test fun laterPagesMoveUpWithTheirItems() {
        val pdf = FakePdfPages()
        pdf.apply(AddItems(1, listOf(item("a"))))
        pdf.apply(AddItems(2, listOf(item("b"))))
        pdf.rotations[2] = 90
        pdf.faultyStrokes[2] = 3

        pdf.addDottedPage(1)

        assertEquals(6, pdf.pageCount)
        assertEquals(listOf("a"), pdf.items(1).map { it.id })
        assertTrue(pdf.isAddedPage(2))
        assertEquals(emptyList<Any>(), pdf.items(2))
        assertEquals(0, pdf.rotation(2))
        assertEquals(listOf("b"), pdf.items(3).map { it.id })
        assertEquals(90, pdf.rotation(3))
        assertEquals(mapOf(3 to 3), pdf.faultyStrokes)
    }

    @Test fun removingAnAddedPageMovesLaterPagesBack() {
        val pdf = FakePdfPages()
        pdf.apply(AddItems(1, listOf(item("b"))))
        pdf.addDottedPage(0)
        pdf.addDottedPage(3)
        pdf.apply(AddItems(1, listOf(item("on-added"))))

        assertThrows(IllegalArgumentException::class.java) { pdf.removeAddedPage(0) }
        pdf.removeAddedPage(1)

        assertEquals(6, pdf.pageCount)
        assertEquals(listOf("b"), pdf.items(1).map { it.id })
        assertFalse(pdf.isAddedPage(1))
        assertTrue(pdf.isAddedPage(3)) // added after the old page 3, which is now page 2
        assertEquals(setOf(3), pdf.addedPages)
    }

    @Test fun theOnlyPageIsNeverRemoved() {
        val pdf = FakePdfPages()
        pdf.addedPages += listOf(0, 1, 2, 3, 4)
        repeat(4) { pdf.removeAddedPage(1) }
        assertEquals(1, pdf.pageCount)
        assertTrue(pdf.isAddedPage(0))
        assertThrows(IllegalArgumentException::class.java) { pdf.removeAddedPage(0) }
        assertEquals(1, pdf.pageCount)
    }
}
